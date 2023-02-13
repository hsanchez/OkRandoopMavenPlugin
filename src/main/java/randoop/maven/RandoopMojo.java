package randoop.maven;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

@Mojo(name = "gentests", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class RandoopMojo extends AbstractMojo {

  @Parameter(required = true)
  private String packageName;
  @Parameter(required = true, defaultValue = "${project.build.outputDirectory}")
  private String sourceDirectory;

  // ${project.basedir}/src/test/java
  @Parameter(required = true, defaultValue = "${project.build.directory}/generated-test-sources/java")
  private String targetDirectory;
  @Parameter(required = true, defaultValue = "60")
  private int timeoutInSeconds;
  @Parameter(defaultValue = "false")
  String permitNonZeroExitStatus;

  @Parameter( defaultValue = "${project}", readonly = true)
  private MavenProject project;

  @Override public void execute() throws MojoExecutionException, MojoFailureException {
    // Resolve any dependencies to the Randoop tool
    final List<URL> urls = new LinkedList<>(resolveProjectClasses());
    urls.addAll(resolveProjectDependencies(project));
    // Add randoop plugin Jar and randoop dependency here
    urls.addAll(resolvePluginJarWithRandoop());

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

    getLog().info((exitCode == 0 ? "Randoop finished." : "Randoop did not finish."));

    // TODO(has) prevent Randoop from creating empty directories for packageName's directories;
    // For some unknown reason, Randoop keeps creating these empty directories; starting with the
    // project name as the root.
    // Temporary fix: Delete infamous directories after executing Randoop.
    Path pathToBeDeleted = project.getBasedir().toPath().resolve("evidentia");
    if (!Files.exists(pathToBeDeleted)) return;
    try (Stream<Path> walk = Files.walk(pathToBeDeleted)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(Util::deleteDir);
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
    try(URLClassLoader classLoader = new URLClassLoader(toURLArray(urls))) {
      final Set<Class<?>> inPackageClasses = Util.classesLookup(packageName, classLoader);
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
      if (exitCode != 0 && !Boolean.parseBoolean(permitNonZeroExitStatus)){
        throw new IOException("Randoop process returned " + exitCode);
      }
    }

    return exitCode;

  }


  private int newCallToRandoop(final List<String> args) throws IOException {
    final ProcessBuilder externalCall = new ProcessBuilder(args);
    externalCall.redirectErrorStream(true);
    externalCall.redirectOutput(Redirect.PIPE);
    externalCall.directory(project.getBasedir());
    getLog().info("Randoop started with time limit of " + timeoutInSeconds + " seconds.");

    final Process randoop = externalCall.start();
    try {
      randoop.waitFor(timeoutInSeconds + 3, TimeUnit.SECONDS);
    } catch (InterruptedException e){
      throw new IOException(e);
    }

    int exitCode = randoop.exitValue();
    if (randoop.exitValue() != 0){
      getLog().info("Exit code " + exitCode);
//      throw new IOException("Randoop process returned " + exitValue);
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

    final Optional<Path> randoopJar = Util.findRandoopJar(pluginJar.getFile(), randoopVersion);
    if (randoopJar.isPresent() && Files.exists(randoopJar.get())){
      try {
        pluginJarUrls.add(newURL(randoopJar.get().toString()));
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
      classesLocation = newURL(sourceDirectory);
    } catch (MalformedURLException e){
      throw new MojoExecutionException("Could not create source path!", e);
    }

    return ImmutableList.of(classesLocation);
  }

  private static URL newURL(final String filepath) throws MalformedURLException {
    return Paths.get(filepath).toUri().toURL();
  }

  private static URL[] toURLArray(final Collection<URL> candidateURLs){
    return candidateURLs.toArray(new URL[0]);
  }

  static class Util {
    static Optional<Path> findRandoopJar(String seedPath, String randoopVersion){
      Optional<Path> parentDir = findParentDir(seedPath, "repository");

      Path repository;
      if (!parentDir.isPresent()){
        final Path orElse = Paths.get(
            System.getProperty("user.home"),
            ".m2",
            "repository");

        if (!Files.exists(orElse)) return Optional.empty();
        repository = orElse;
      } else {
        repository = parentDir.get();
      }

      final Path randoopJar = repository.resolve("randoop")
          .resolve("randoop-all")
          .resolve(randoopVersion)
          .resolve("randoop-all-" + randoopVersion +".jar");

      if (!Files.exists(randoopJar)) return Optional.empty();

      return Optional.of(randoopJar);
    }

    static Optional<Path> findParentDir(String filepath, String targetName){
      // Only valid dir names allowed
      Preconditions.checkArgument(!Strings.isNullOrEmpty(targetName));

      Path current = Paths.get(filepath);
      if (!Files.exists(current)) return Optional.empty();

      while(current != null && !current.getFileName().toString().equals(targetName)){
        current = current.getParent();
      }

      return Optional.ofNullable(current);
    }

    static Set<Class<?>> classesLookup(final String packageName, ClassLoader loader) throws IOException {
      Preconditions.checkNotNull(packageName);

      final ClassLoader lenientClassLoader = Optional
          .ofNullable(loader)
          .orElse(ClassLoader.getSystemClassLoader());

      return ClassPath.from(lenientClassLoader)
          .getAllClasses()
          .stream()
          .filter(cls -> cls.getPackageName().equalsIgnoreCase(packageName))
          .map(ClassInfo::load)
          .collect(Collectors.toSet());
    }


    static void deleteDir(Path path){
      try {
        Files.delete(path);
      } catch (IOException ignored){}
    }
  }
}
