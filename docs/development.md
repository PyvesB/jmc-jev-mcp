# Development

## Building

```
mvn package                      # uber-jar at target/jmc-jev-mcp-0.1.0-SNAPSHOT-runner.jar
JAVA_HOME=<graalvm> mvn package -Dnative -DskipTests   # native binary
```

Depends on the published `org.openjdk.jmc` core artifacts, so no local JMC build is required.

The build enforces formatting with Spotless (tabs, 120 columns); `mvn spotless:apply` fixes it.

## Testing

```
mvn test
```

- Most tests run against `src/test/resources/recordings/wldf.jfr`, a real WebLogic recording.
- `JudgmentToolsLiveTest` calls Jev and is skipped unless `JEV_KEY` is set. The fail-fast tests in
  `JudgmentToolsTest` check the opposite case and are skipped when it is set.
- Container metrics are tested against `container-synthetic.jfr`, a small recording of synthetic
  events with the same field layout as the JDK's container events. To regenerate it, run
  `SyntheticContainerRecording` on a JVM that is not containerized (e.g. on macOS) - see its
  Javadoc.
- `NativeImageSanityIT` starts a native binary and checks that it answers an MCP `tools/list`
  request. It is skipped unless `native.image.path` points to a binary:

  ```
  mvn verify -Dnative -Dnative.image.path=target/jmc-jev-mcp-0.1.0-SNAPSHOT-runner
  ```

## Demoing

```
scripts/demo.sh                  # or: mvn -q test -Pdemo
```

Runs `classifyBiggestIssue`, `classifyWorkloadProfile` and `assessRuleResults` against `wldf.jfr`
and prints the results. Requires `JEV_KEY`.

## Releasing

Pushing a `v*` tag runs the release workflow, which builds the uber-jar and native binaries for
Linux (x86_64, aarch64), macOS (aarch64) and Windows (x86_64), runs the native sanity test on
each, and attaches them all to a GitHub release.
