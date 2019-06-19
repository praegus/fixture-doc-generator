## JSON JavaDoc Doclet
#### For FitNesse Fixture Docs

A doclet class to collect Javadoc info from fixture classes to be distributed in the META-INF folder of the fixture jar.

Use Praegus toolchain-fitnesse-plugin (https://repo.maven.apache.org/maven2/nl/praegus/toolchain-fitnesse-plugin/) to see the documentation in your test's context pane when editing.

If you define a line in your javadoc comment that starts with 'Usage: ' the wiki usage is expected. So say we have a method:

```
public String getSomeDataFromButIgnore(String location, String somethingElse) {}
```

One could write:
```
/**
 * A method that gets data
 * Usage: | get some data from | [location] | but ignore | [somethingElse] |
 * @Param location ..
 * @Param somethingElse ..
 * @Return the requested data
 */
```
To be sure that the context helper and autocomplete will use that string instead of:

```
| get some data from but | [location] | ignore | [somethingElse] |
```
Which the default wikitext generator in autocomplete responder would produce.

Usage:
Add the doclet to your build using the maven-javadoc-plugin.
Note that in order to document any methods that come from superclasses that are not in your package, you should set `includeDependencySources` to true and exclude any packages (you can use wildcards) that you don't need json files for.
```
<plugin>
    <artifactId>maven-javadoc-plugin</artifactId>
    <extensions>true</extensions>
    <executions>
        <execution>
            <phase>generate-resources</phase>
            <goals>
                <goal>javadoc</goal>
            </goals>
        </execution>
    </executions>

    <configuration>
        <doclet>nl.praegus.doclets.FixtureDocGenerator</doclet>
        <docletArtifact>
            <groupId>nl.praegus</groupId>
            <artifactId>fixture-doc-generator</artifactId>
            <version>1.0</version>
        </docletArtifact>

        <excludePackageNames>list:of:packages:to:exclude</excludePackageNames>

        <includeDependencySources>true</includeDependencySources>
        <dependencySourceIncludes>
            <dependencySourceInclude>group:artifactToInclude</dependencySourceInclude>--&gt;
        </dependencySourceIncludes>

        <reportOutputDirectory>${project.basedir}/src/main/resources/META-INF/</reportOutputDirectory>
        <useStandardDocletOptions>false</useStandardDocletOptions>
    </configuration>
</plugin>
```