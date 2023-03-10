package randoop.maven;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import randoop.maven.utils.Utils;

/**
 * Entry point to the Randoop maven plugin; more specifically to
 * the 'gentests' goal.
 *
 * Before execution, the plugin checks if tests have been run and
 * there are any surefire reports. If there are any, the plugin
 * will persist them, re-run Randoop, and then let surefire run the
 * regenerated tests. The last step will re-generate a new surefire report,
 * which the Soundness test verifier will pick up and compare it against
 * the previously generated.
 *
 */
@SuppressWarnings("unused")
@Mojo(name = "gentests", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class RandoopMojo extends AbstractMojo {

  @Parameter(required = true) private String packageName;
  @Parameter(required = true, defaultValue = "${project.build.outputDirectory}")
  private String sourceDirectory;
  @Parameter(required = true, defaultValue = "${project.build.directory}/generated-test-sources/java")
  private String targetDirectory;
  @Parameter(required = true, defaultValue = "60") private int timeoutInSeconds;
  @Parameter(defaultValue = "false") private boolean permitNonZeroExitStatus;
  @Parameter(defaultValue = "etb2") private String rootJavaPackage;
  @Parameter(defaultValue = "${project.build.directory}/surefire-reports/")
  private String surefireReportsDir;

  /** Skip checking licence compliance */
  @Parameter(property = "randoop.skip", defaultValue = "false")
  private boolean skipRandoop;

  @Parameter(property = "randoop.skip", defaultValue = "false")
  private boolean alwaysColdStart;

  @Parameter( defaultValue = "${project}", readonly = true)
  private MavenProject project;

  @Override public void execute() throws MojoExecutionException, MojoFailureException {
    if (skipRandoop){
      getLog().info("skipping Randoop execution");
      return;
    }

    // Check if surefire reports have been generated. If they have,
    // copy them to ${project.basedir}/.surefire.d
    saveCopyOfSurefireReportsIfExist();

    // Resolve any dependencies to the Randoop tool
    final List<URL> urls = resolveRandoopDependencies();
    final List<String> args = buildArgs(urls);

    // Build Randoop command line
    final String randoopCmdLine = String.join(" ", args);
    getLog().info("Call outside Maven: " + randoopCmdLine);

    int exitCode = 0;
    try {
      exitCode = executeRandoop(args);
    } catch (IOException | InterruptedException e){
      throw new MojoFailureException(
          this,
          "Randoop encountered an error!",
          "Test generation failure. Process exited with code " + exitCode);
    }

    getLog().info("Randoop " + (exitCode == 0 ? "finished." : "did not finish."));

    // TODO(has) Prevent Randoop from generating empty directories matching the packageName
    // Temp. fix: search for those empty directories and delete them if found
    removesRandoopLeftovers();
  }

  private List<URL> resolveRandoopDependencies() throws MojoExecutionException {
    final List<URL> urls = new LinkedList<>(resolveProjectClasses());
    urls.addAll(resolveProjectDependencies(project));
    // Add randoop plugin Jar and randoop dependency here
    urls.addAll(resolvePluginJarWithRandoop());
    return urls;
  }

  private void saveCopyOfSurefireReportsIfExist() throws MojoFailureException {
    final Predicate<File> isRandoopSuite = Utils.newRandoopSurefireReportPredicate(packageName);

    final Path baseDir = project.getBasedir().toPath();

    // Cold start scenario:
    // Check previous surefire report file at '${project.build.directory}/surefire-reports/'
    // and persists a copy of its Randoop unit test report ONLY if we have not done so.
    final Path surefirePath = Paths.get(surefireReportsDir);
    final Path surefireCopyPath = baseDir.resolve(".surefire.d");
    if (alwaysColdStart){
      Utils.deleteDirQuietly(surefireCopyPath);
    }

    if (Files.exists(surefirePath) && !Files.exists(surefireCopyPath)){
      // '${project.build.directory}/surefire-reports/${packageName}.[Regression|Error]Test.txt'
      Set<Path> matchedFiles = Utils.findFiles(surefirePath, Utils.TEXT_MATCHER).stream()
          .map(Path::toFile)
          .filter(isRandoopSuite)
          .map(File::toPath)
          .collect(ImmutableSet.toImmutableSet());

      for (Path each : matchedFiles){
        try {
          Utils.copyFiles(baseDir, surefireCopyPath, matchedFiles);
        } catch (IOException e) {
          throw new MojoFailureException("Unable to copy surefire reports!", e);
        }
      }
    } // Otherwise, Run Randoop as usual
  }

  private void removesRandoopLeftovers() {
    Path pathToBeDeleted = project.getBasedir().toPath().resolve(rootJavaPackage);
    if (!Files.exists(pathToBeDeleted))
      return;
    try (Stream<Path> walk = Files.walk(pathToBeDeleted)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(Utils::deleteDirQuietly);
    } catch (IOException e){
      getLog().warn("Could not delete " + pathToBeDeleted + " Error details: " + e.getMessage());
    }
  }

  private List<String> buildArgs(final List<URL> urls) throws MojoExecutionException {
    Preconditions.checkNotNull(urls);
    final String classpath = urls.stream()
        .map(URL::toString)
        .collect(Collectors.joining(File.pathSeparator));

    final List<String> args = Lists.newLinkedList();

    // Build up Randoop command line
    args.add("java");
    args.add("-ea");
    args.add("-classpath");
    args.add(classpath);
    args.add("randoop.main.Main");
    args.add("gentests");
    args.add("--time-limit=" + timeoutInSeconds);
    args.add("--debug-checks=true");
    args.add("--junit-package-name=" + packageName);
    args.add("--junit-output-dir=" + targetDirectory);

    // Add project classes
    try(URLClassLoader classLoader = new URLClassLoader(Utils.toURLArray(urls))) {
      final Set<Class<?>> inPackageClasses = Utils.classesLookup(packageName, classLoader);
      for (Class<?> eachClass : inPackageClasses){
        getLog().info("Add class " + eachClass.getName());
        args.add("--testclass=" + eachClass.getName());
      }
    } catch (IOException io){
      throw new MojoExecutionException("Could add testclass!", io);
    }

    return args;
  }


  public int executeRandoop(final List<String> args) throws IOException, InterruptedException{
    final ProcessBuilder processBuilder = new ProcessBuilder()
        .command(args)
        .redirectErrorStream(true);
    final Process process = processBuilder.start();

    int exitCode;
    try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String outputLine;
      while ((outputLine = bufferedReader.readLine()) != null) {
        getLog().info(outputLine);
      }

      process.waitFor(timeoutInSeconds + 3, TimeUnit.SECONDS);

      exitCode = process.exitValue();
      if (exitCode != 0 && !permitNonZeroExitStatus){
        throw new IOException("Randoop process returned " + exitCode);
      }
    }

    return exitCode;

  }


  private Set<URL> resolvePluginJarWithRandoop(){
    final List<URL> pluginJarUrls = new ArrayList<>();
    final URL pluginJar = getClass().getProtectionDomain().getCodeSource().getLocation();
    pluginJarUrls.add(pluginJar);

    getLog().debug("Seed dir: " + Paths.get(pluginJar.getFile()).getParent());

    final Optional<String> optionalRandoopVersion = Optional.ofNullable(project.getProperties().getProperty("revision"));
    final String randoopVersion = optionalRandoopVersion.orElse("4.3.2");

    getLog().debug("Current Randoop version: " + randoopVersion);

    final Optional<Path> randoopJar = Utils.findRandoopJar(pluginJar.getFile(), randoopVersion);
    if (randoopJar.isPresent() && Files.exists(randoopJar.get())){
      try {
        pluginJarUrls.add(Utils.newURL(randoopJar.get().toString()));
      } catch (MalformedURLException e) {
        getLog().warn("Unable to find Randoop Jar", e);
      }
    }

    return ImmutableSet.copyOf(pluginJarUrls);
  }

  private static List<URL> resolveProjectDependencies(final MavenProject project) throws
      MojoExecutionException {
    Preconditions.checkNotNull(project);

    final List<URL> urls = Lists.newLinkedList();
    try {
      for (final Artifact each : project.getArtifacts()){
        urls.add(each.getFile().toURI().toURL());
      }

    } catch (MalformedURLException e){
      throw new MojoExecutionException("Could add artifact!", e);
    }

    return urls;
  }


  private List<URL> resolveProjectClasses() throws MojoExecutionException {
    final URL classesLocation;
    try {
      classesLocation = Utils.newURL(sourceDirectory);
    } catch (MalformedURLException e){
      throw new MojoExecutionException("Could not create source path!", e);
    }

    return ImmutableList.of(classesLocation);
  }

}
