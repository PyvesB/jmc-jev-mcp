# jmc-jev-mcp

[![Build](https://github.com/thegreystone/jmc-jev-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/thegreystone/jmc-jev-mcp/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/thegreystone/jmc-jev-mcp)](https://github.com/thegreystone/jmc-jev-mcp/releases/latest)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.38-blueviolet)](https://quarkus.io/)
[![GraalVM Native](https://img.shields.io/badge/GraalVM-native--image-orange)](https://www.graalvm.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green)](https://opensource.org/licenses/MIT)

An MCP server for JDK Flight Recorder (JFR) recordings that goes one step past listing findings:
it uses [TypeSafe's Jev model](https://typesafe.ai) to judge them.

- `getRuleResults` runs JMC's built-in automated analysis rules (the same engine JDK Mission
  Control uses) against a loaded recording and reports every triggered finding.
- `classifyBiggestIssue` asks Jev which of those findings is the dominant root cause, optionally
  weighted by a symptom you describe.
- `classifyWorkloadProfile` computes metrics, histograms, time series and pruned call graphs from
  the recording and asks Jev whether the workload looks throughput oriented, pause-time
  sensitive, memory constrained, allocation heavy, cpu bound, lock contended, and/or still
  warming up - these are independent judgments, not mutually exclusive.
- `assessRuleResults` sends all of JMC's rule results together with that same data, and asks Jev,
  for every rule, how likely a high-severity finding from it would be correct - flagging warnings
  the data does not support, and problems the data shows that JMC rated lower.

Built with [Quarkus](https://quarkus.io) and the
[`quarkus-mcp-server-stdio`](https://github.com/quarkiverse/quarkus-mcp-server) extension,
compiled to a GraalVM native image for fast STDIO startup. Depends on the published
`org.openjdk.jmc` core artifacts (no local JMC build required).

## Tools

| Tool | Needs `JEV_KEY` | What it does |
|------|:---:|------|
| `loadRecording` | | Loads a `.jfr` file by absolute path and returns its `recordingId`. Call this first. |
| `listRecordings` | | Lists the loaded recordings. |
| `getRecordingInfo` | | Event count, event type count and duration of a recording. |
| `unloadRecording` | | Frees a recording and its cached rule results. |
| `getRuleResults` | | JMC's automated analysis findings, filtered by minimum severity. |
| `classifyBiggestIssue` | yes | Jev's pick of the dominant finding, with confidence and per-finding probabilities. |
| `classifyWorkloadProfile` | yes | Jev's likely/possible/unlikely judgment for each workload label. |
| `assessRuleResults` | yes | Jev's estimate, per rule, of how likely a high-severity finding would be correct. |
| `getVersion` | | The server version. |

Apart from `unloadRecording`, the `recordingId` argument can be left empty when only one
recording is loaded.

### What `classifyWorkloadProfile` sends to Jev

Jev cannot call back into the server, so everything it gets to see is assembled up front and sent
in a single request (typically a few tens of KB):

- **Metrics** - GC count, pause overhead and max pause, allocation rate, average CPU load.
- **Warm-up** - JVM uptime at the start and end of the recording, class loading and thread start
  rates.
- **Environment** - CPU count, heap size, collectors, physical memory, RSS peak and, when the JVM
  runs in a container, its CPU quota, memory limits, CPU throttling and memory-limit hits.
- **Time series** - the series JMC charts on its Java Application and Heap pages (CPU, threads,
  RSS, heap, physical memory, allocation, GC pauses), plus heap used after GC, loaded classes and
  container usage, downsampled into 20 slices of the recording.
- **Duration histograms** - for monitor enter, thread park, GC pauses, time-to-safepoint and VM
  operations at safepoints. Backed by HdrHistogram like JMC's own percentile tables, with
  log-scale buckets, percentiles, and the recording threshold that cuts off the low end.
- **Lock contention** - monitor classes and park blockers ranked by total blocked time, which
  tells real lock contention apart from idle pool threads waiting for work.
- **Hot paths** - call graphs for execution samples, allocations and monitor enters, cut down with
  JMC's own entropy-based pruning so caller/callee structure survives. The `maxHotPathNodes`
  argument sets the node budget per graph (default 80, capped at 500).

Jev limits how large the state of a request may be. If the state does not fit, the hot-path
graphs - by far its largest part - are pruned further, and the tool output says so.
`assessRuleResults` additionally sends every rule's result, with the full explanation and solution
text for INFO and WARNING results.

Each section also reports which of its event types were enabled in the recording, so that a
metric that is zero because its event was disabled is treated as missing data rather than as
evidence.

## Installation

Download a native binary for your platform, or the platform-independent uber-jar, from the
[latest release](https://github.com/thegreystone/jmc-jev-mcp/releases/latest). The uber-jar
needs Java 21 or later.

Jev-backed tools require a TypeSafe API key in the `JEV_KEY` environment variable. Every other
tool works without it. The key is read when a tool is called, so it has to be set in the
environment the MCP client starts the server in.

With Claude Code:

```
export JEV_KEY=...
claude mcp add jmc-jev -- /path/to/jmc-jev-mcp-<version>-macos-aarch64
# or
claude mcp add jmc-jev -- java -jar /path/to/jmc-jev-mcp-<version>-runner.jar
```

With any other MCP client that takes a JSON server configuration:

```json
{
  "mcpServers": {
    "jmc-jev": {
      "command": "/path/to/jmc-jev-mcp-<version>-macos-aarch64",
      "env": { "JEV_KEY": "..." }
    }
  }
}
```

Then ask the client to load a recording and classify it, e.g. "Load /tmp/app.jfr and tell me
what kind of workload it is, and what the biggest problem is."

The server talks MCP over STDIO, so stdout is reserved for the protocol. Logs go to
`jmc-jev-mcp-server.log` in the server's working directory.

## Building

```
mvn package                      # uber-jar at target/jmc-jev-mcp-0.1.0-SNAPSHOT-runner.jar
JAVA_HOME=<graalvm> mvn package -Dnative -DskipTests   # native binary
```

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

## Releasing

Pushing a `v*` tag runs the release workflow, which builds the uber-jar and native binaries for
Linux (x86_64, aarch64), macOS (aarch64) and Windows (x86_64), runs the native sanity test on
each, and attaches them all to a GitHub release.

## Security note

Event data inside a JFR recording (thread names, class names, stack frames, log messages) comes
from the profiled application and is untrusted. Tool descriptions call this out explicitly, and
Jev is only ever shown JMC's own structured findings and computed metrics as evidence to judge -
never asked to follow instructions found inside recording data.

## License

[MIT](LICENSE)
