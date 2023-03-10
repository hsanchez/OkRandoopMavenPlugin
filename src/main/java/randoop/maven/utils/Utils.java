package randoop.maven.utils;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Utils {

  public static final PathMatcher TEXT_MATCHER = FileSystems.getDefault().getPathMatcher("glob:*.txt");


  private Utils(){
    throw new Error("Cannot be instantiated!");
  }

  public static Predicate<File> newRandoopSurefireReportPredicate(String packageName){
    return f -> f.getName().equalsIgnoreCase(
        packageName  + ".RegressionTest.txt")
        || f.getName().equalsIgnoreCase(
        packageName + ".ErrorTest.txt");
  }

  public static Path copyFiles(Path src, Path tgt, Collection<Path> files) throws IOException {
    Preconditions.checkArgument(Files.exists(src));
    Preconditions.checkNotNull(tgt);

    if (!Files.exists(tgt)){
      Files.createDirectory(tgt);
    }

    assert Files.exists(tgt);

    for (Path each : files) {
      try {
        Files.copy(
            each,
            tgt.resolve(each.getFileName()),
            StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        // failed to copy files
        throw new RuntimeException(e.getMessage(), e);
      }
    }

    return tgt;
  }

  /**
   * List all Java files found in a directory. Skip those files matching the provided skip hints.
   *
   * @param directory   directory to walk into.
   * @param pathMatcher strategy for match operations on paths
   * @param skipHints   hints for files to be excluded in the directory.
   * @return the list of files matching a given extension.
   */
  public static List<Path> findFiles(Path directory, PathMatcher pathMatcher, String... skipHints) {
    if (!Files.isDirectory(directory) || !Files.exists(directory)) {
      return ImmutableList.of();
    }

    final Set<String> skipUniverse = Arrays.stream(skipHints)
        .filter(Objects::nonNull)
        .collect(ImmutableSet.toImmutableSet());

    final List<Path> results = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(directory)) {
      walk.filter(Files::isReadable)
          .filter(Files::isRegularFile)
          .filter(file -> pathMatcher.matches(file.getFileName()))
          .filter(file -> !Utils.anyMatches(file, skipUniverse))
          .forEach(results::add);
    } catch (IOException ignored) {
    }

    return results;
  }

  public static Optional<Path> findRandoopJar(String seedPath, String randoopVersion) {
    Optional<Path> parentDir = findParentDir(seedPath, "repository");

    Path repository;
    if (parentDir.isEmpty()) {
      final Path orElse = Paths.get(
          System.getProperty("user.home"),
          ".m2",
          "repository");

      if (!Files.exists(orElse)) {
        return Optional.empty();
      }
      repository = orElse;
    } else {
      repository = parentDir.get();
    }

    final Path randoopJar = repository.resolve("randoop")
        .resolve("randoop-all")
        .resolve(randoopVersion)
        .resolve("randoop-all-" + randoopVersion + ".jar");

    if (!Files.exists(randoopJar)) {
      return Optional.empty();
    }

    return Optional.of(randoopJar);
  }

  public static Optional<Path> findParentDir(String filepath, String targetName) {
    // Only valid dir names allowed
    Preconditions.checkArgument(!Strings.isNullOrEmpty(targetName));

    Path current = Paths.get(filepath);
    if (!Files.exists(current)) {
      return Optional.empty();
    }

    while (current != null && !current.getFileName().toString().equals(targetName)) {
      current = current.getParent();
    }

    return Optional.ofNullable(current);
  }

  public static Set<Class<?>> classesLookup(final String packageName, ClassLoader loader)
      throws IOException {
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

  public static boolean isDirEmpty(Path path) throws IOException {
    if (Files.isDirectory(path)) {
      try (Stream<Path> entries = Files.list(path)) {
        return entries.findFirst().isEmpty();
      }
    }

    return false;
  }


  public static void deleteDirQuietly(Path path) {
    if (!Files.exists(path)) return;

    try {
      if (isDirEmpty(path)){
        Files.delete(path);
      } else {
        // Delete a non-empty directory recursively
        try (Stream<Path> walk = Files.walk(path)) {
          //noinspection ResultOfMethodCallIgnored
          walk.sorted(Comparator.reverseOrder())
              .map(Path::toFile)
              .forEach(File::delete);
        }
      }
    } catch (IOException ignored) {
    }

    assert !Files.exists(path);

  }

  static boolean anyMatches(Path file, Set<String> skipUniverse) {
    return skipUniverse.stream().anyMatch(entry -> file.getFileName().toString().contains(entry));
  }

  public static URL newURL(final String filepath) throws MalformedURLException {
    return Paths.get(filepath).toUri().toURL();
  }

  public static URL[] toURLArray(final Collection<URL> candidateURLs){
    return candidateURLs.toArray(new URL[0]);
  }
}
