# How it works

jmc-jev-mcp parses JFR recordings with the published `org.openjdk.jmc` core libraries - the same
parser and automated analysis rules JDK Mission Control uses - and runs as an MCP server over
STDIO. It is built with [Quarkus](https://quarkus.io) and the
[`quarkus-mcp-server-stdio`](https://github.com/quarkiverse/quarkus-mcp-server) extension, and
compiled to a GraalVM native image for fast startup.

## The Jev-backed tools

- `classifyBiggestIssue` sends the triggered rule results (severity, score, summary and
  explanation) and the optional symptom, and asks Jev a single choice question: which finding is
  the dominant root cause.
- `classifyWorkloadProfile` sends the state described below and asks one independent yes/no
  question per workload label, so the labels are not mutually exclusive.
- `assessRuleResults` sends the same state plus every rule's result, and asks one yes/no question
  per rule: how likely it is that the problem the rule looks for is significant in this
  recording. Jev and JMC are counted as disagreeing when JMC warned but Jev puts the probability
  below 0.3, or when JMC rated the rule OK or INFO but Jev puts it at 0.7 or above. For rules that
  reported a finding (INFO or WARNING) it also asks whether the finding is worth acting on, since
  a finding such as truncated stack traces can be useful without the problem being significant
  for the application.

Probabilities of 0.7 and above are reported as likely, below 0.3 as unlikely, and anything in
between as possible.

## What is sent to Jev

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

## Security

Event data inside a JFR recording (thread names, class names, stack frames, log messages) comes
from the profiled application and is untrusted. Tool descriptions call this out explicitly, and
Jev is only ever shown JMC's own structured findings and computed metrics as evidence to judge -
never asked to follow instructions found inside recording data.
