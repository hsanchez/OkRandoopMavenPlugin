# OK Randoop Maven Plugin
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

(Experimental) Randoop Maven Plugin

This Maven plugin creates a task named `gentests` to run [Randoop](https://randoop.github.io/randoop/) on Java projects.

### Installation

```shell
> git clone https://github.com/hsanchez/OkRandoopMavenPlugin.git
```

Then install it on maven's local repository,

```shell
> cd OkRandoopMavenPlugin; mvn clean install
```

During its install, Randoop's latest version (e.g., `4.3.2` version) will 
be downloaded to maven's local repository.

Once installed, please proceed to its configuration in ETB2's `pom.xml` file.


## Configuration

First step is to add the `randoop-maven-plugin` to ETB2's pom file.

```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.certibus</groupId>
      <artifactId>randoop-maven-plugin</artifactId>
      <version>1.0-SNAPSHOT</version>
      <configuration>
        <!-- -->
        <packageName>etb2.engine.utils</packageName>
        <targetDirectory>${project.basedir}/src/test/java</targetDirectory>
      </configuration>
      <executions>
        <execution>
          <id>generate-tests</id>
          <goals>
            <goal>gentests</goal>
          </goals>
          <phase>generate-test-sources</phase>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

