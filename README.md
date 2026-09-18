# jmc-jev-mcp

An MCP server for JDK Flight Recorder (JFR) recordings that goes one step past listing findings:
it uses [TypeSafe's Jev model](https://typesafe.ai) to judge them.

- `getRuleResults` runs JMC's built-in automated analysis rules (the same engine JDK Mission
  Control uses) against a loaded recording and reports every triggered finding.
- `classifyBiggestIssue` asks Jev which of those findings is the dominant root cause, optionally
  weighted by a symptom you describe.
- `classifyWorkloadProfile` computes GC, allocation, and CPU metrics from the recording and asks
  Jev whether the workload looks throughput-oriented, pause-time sensitive, memory constrained,
  allocation heavy, and/or cpu bound - these are independent judgments, not mutually exclusive.

Built with [Quarkus](https://quarkus.io) and the
[`quarkus-mcp-server-stdio`](https://github.com/quarkiverse/quarkus-mcp-server) extension,
compiled to a GraalVM native image for fast STDIO startup. Depends on the published
`org.openjdk.jmc` core artifacts (no local JMC build required).

## Building

```
mvn package                      # uber-jar at target/jmc-jev-mcp-0.1.0-SNAPSHOT-runner.jar
JAVA_HOME=<graalvm> mvn package -Dnative -DskipTests   # native binary
```

## Running

Jev-backed tools require a TypeSafe API key in the `JEV_KEY` environment variable. Every other
tool works without it.

```
export JEV_KEY=...
claude mcp add jmc-jev -- <path-to-binary or java -jar ...-runner.jar>
```

## Security note

Event data inside a JFR recording (thread names, class names, stack frames, log messages) comes
from the profiled application and is untrusted. Tool descriptions call this out explicitly, and
Jev is only ever shown JMC's own structured findings and computed metrics as evidence to judge -
never asked to follow instructions found inside recording data.
