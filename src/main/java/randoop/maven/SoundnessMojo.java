package randoop.maven;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import randoop.maven.utils.Util;

@SuppressWarnings("unused")
@Mojo(
    name = "test-soundness",
    requiresDependencyResolution = ResolutionScope.TEST,
    defaultPhase = LifecyclePhase.TEST)
public class SoundnessMojo extends AbstractMojo {
  @Parameter(required = true)
  private String packageName;
  @Parameter(defaultValue = "${project.build.directory}/surefire-reports/")
  private String surefireReportsDir;
  @Parameter(required = true, defaultValue = "true")
  private boolean failOnDivergence;

  @Parameter( defaultValue = "${project}", readonly = true)
  private MavenProject project;

  @Override public void execute() throws MojoExecutionException, MojoFailureException {
    final Predicate<File> isRandoopSuite = Util.newRandoopSurefireReportPredicate(packageName);
    final Path surefirePath = Paths.get(surefireReportsDir);
    if (Files.exists(surefirePath)){
      // Grab the most recent surefire report; e.g., either both RegressionTest
      // and ErrorTest or one of them)
      Set<Path> curReports = Util.findFiles(surefirePath, Util.TEXT_MATCHER).stream()
          .map(Path::toFile)
          .filter(isRandoopSuite)
          .map(File::toPath)
          .collect(ImmutableSet.toImmutableSet());
      assert curReports.size() == 2;

      // Grab the previous surefire report (if available)
      final Path hiddenSurefireDir = project.getBasedir().toPath().resolve(".surefire.d");
      Set<Path> prevReports = Util.findFiles(hiddenSurefireDir, Util.TEXT_MATCHER).stream()
          .map(Path::toFile)
          .filter(isRandoopSuite)
          .map(File::toPath)
          .collect(ImmutableSet.toImmutableSet());

      // Pair up new and previous Regression test reports, and new and previous Error test reports.
      @SuppressWarnings("UnstableApiUsage")
      Set<Map.Entry<Path, Path>> pairUp = Streams.zip(curReports.stream(),
              prevReports.stream(),
              Maps::immutableEntry)
          .filter(e -> e.getKey()
              .toFile()
              .getName()
              .equalsIgnoreCase(e.getValue().toFile().getName()))
          .collect(Collectors.toSet());

      // Randoop's old and new tests should not change. If they do, then
      // the tested project contains a likely new bug
      final SoundnessChecker soundnessChecker = new SoundnessChecker(pairUp);
      soundnessChecker.passOrThrow(failOnDivergence);
    }
  }

  static class SoundnessChecker {
    private final List<Map<String, Integer>> outputList;

    SoundnessChecker(Set<Map.Entry<Path, Path>> pairUp) throws MojoFailureException {
      this.outputList = Lists.newArrayList();
      for (Map.Entry<Path, Path> each : pairUp){
        try {
          final Optional<Map<String, Integer>> x = processFile(each.getKey());
          final Optional<Map<String, Integer>> y = processFile(each.getValue());

          x.ifPresent(outputList::add);
          y.ifPresent(outputList::add);

        } catch (IOException e) {
          throw new MojoFailureException("Cannot process surefire report file");
        }
      }
    }

    static Optional<Map<String, Integer>> processFile(Path file) throws IOException {
      try (Scanner scanner =  new Scanner(file, StandardCharsets.UTF_8)){
        while (scanner.hasNextLine()){
          final Map<String, Integer> record = processNextLine(scanner.nextLine());
          if (record.isEmpty()) continue;
          return Optional.of(record);
        }
      }

      return Optional.empty();
    }

    static Map<String, Integer> processNextLine(String line) {
      if (!line.startsWith("Tests run:")) return ImmutableMap.of();
      return Arrays.stream(line.split(", "))
          .map(String::trim).map(next -> next.split(": "))
          .filter(entry -> !entry[0].equalsIgnoreCase("Time elapsed"))
          .collect(Collectors.toMap(entry -> entry[0], entry -> Integer.parseInt(entry[1])));
    }

    void passOrThrow(boolean failOnDivergence) throws SoundnessError {
      if (outputList.size() != 2 || !failOnDivergence) return;

      if (!Maps.difference(outputList.get(0), outputList.get(1)).areEqual()){
        throw new SoundnessError("Your Randoop unit tests before and after have diverged");
      }
    }
  }

  static class SoundnessError extends RuntimeException {
    public SoundnessError(String message) {
      super(message);
    }
  }
}
