# jmc-jev-mcp

[![Build](https://github.com/thegreystone/jmc-jev-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/thegreystone/jmc-jev-mcp/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/thegreystone/jmc-jev-mcp)](https://github.com/thegreystone/jmc-jev-mcp/releases/latest)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.38-blueviolet)](https://quarkus.io/)
[![GraalVM Native](https://img.shields.io/badge/GraalVM-native--image-orange)](https://www.graalvm.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green)](https://opensource.org/licenses/MIT)

Ask your AI assistant what is wrong with a Java application, and get an answer grounded in a
JDK Flight Recorder (JFR) recording.

jmc-jev-mcp is an [MCP](https://modelcontextprotocol.io) server that gives assistants such as
Claude Code the automated analysis of JDK Mission Control (JMC), and goes one step further: it
uses [TypeSafe's Jev model](https://typesafe.ai) to judge the findings, instead of just listing
them.

## What you get

- **JMC's automated analysis, from your assistant.** The same rules JDK Mission Control runs on a
  recording - GC, allocation, locking, I/O, threads, JIT, configuration and more - with their
  findings, explanations and suggested fixes.
- **The biggest issue, picked for you.** Instead of a long list of warnings, Jev judges which
  finding is the dominant root cause, optionally weighed against a symptom you describe, such as
  "high tail latency under load".
- **A workload profile.** Jev judges whether the application looks throughput oriented,
  pause-time sensitive, memory constrained, allocation heavy, CPU bound, lock contended, and
  whether it was still warming up when the recording was made. Each is a separate
  likely/possible/unlikely judgment with a probability, since a workload can be several of
  these at once.
- **A second opinion on JMC's rules.** For every rule, Jev estimates how likely it is that the
  problem the rule looks for is significant, given the data in the recording, and points out where
  it disagrees with JMC: warnings the data does not support, and problems the data shows that JMC
  rated lower.

The judgments are based on metrics, histograms, time series and call graphs computed from the
recording - not just on the rule texts. See [How it works](docs/how-it-works.md) for what is
computed and sent.

## Getting started

### 1. Download

Get a native binary for your platform from the
[latest release](https://github.com/thegreystone/jmc-jev-mcp/releases/latest):

| Platform | File |
|----------|------|
| macOS (Apple silicon) | `jmc-jev-mcp-<version>-macos-aarch64` |
| Linux (x86_64) | `jmc-jev-mcp-<version>-linux-x86_64` |
| Linux (aarch64) | `jmc-jev-mcp-<version>-linux-aarch64` |
| Windows (x86_64) | `jmc-jev-mcp-<version>-windows-x86_64.exe` |
| Any platform with Java 21+ | `jmc-jev-mcp-<version>-runner.jar` |

On macOS and Linux, make the binary executable. On macOS, also clear the quarantine flag the
browser sets on downloads, or the system will refuse to run it:

```
chmod +x jmc-jev-mcp-<version>-macos-aarch64
xattr -d com.apple.quarantine jmc-jev-mcp-<version>-macos-aarch64
```

### 2. Get a TypeSafe API key

The Jev-backed tools need an API key from [TypeSafe](https://typesafe.ai), provided in the
`JEV_KEY` environment variable. Without it, you can still load recordings and get JMC's rule
results; only the Jev judgments are unavailable.

### 3. Add the server to your assistant

With Claude Code:

```
export JEV_KEY=...
claude mcp add jmc-jev -- /path/to/jmc-jev-mcp-<version>-macos-aarch64
# or, with the jar
claude mcp add jmc-jev -- java -jar /path/to/jmc-jev-mcp-<version>-runner.jar
```

The key is read from the environment the assistant starts the server in, so export it before
starting Claude Code.

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

### 4. Ask about a recording

Point the assistant at a `.jfr` file by its absolute path, for example:

- "Load /tmp/app.jfr and tell me what the biggest problem is."
- "What kind of workload is in /tmp/app.jfr? Is it still warming up?"
- "Users complain about latency spikes. What in /tmp/app.jfr could explain that?"
- "Which of JMC's warnings for this recording should I actually believe?"

Don't have a recording yet? Start your application with
`-XX:StartFlightRecording:duration=60s,filename=/tmp/app.jfr,settings=profile`, or record a
running one with `jcmd <pid> JFR.start duration=60s filename=/tmp/app.jfr settings=profile`.
The `profile` settings record more of the events the analysis uses than the `default` ones.

## Tools

The assistant picks the right tool from your question; this is what it has to work with.

| Tool | Needs `JEV_KEY` | What it does |
|------|:---:|------|
| `loadRecording` | | Loads a `.jfr` file by absolute path. |
| `listRecordings` | | Lists the loaded recordings. |
| `getRecordingInfo` | | Event count, event type count and duration of a recording. |
| `unloadRecording` | | Frees a recording and its cached results. |
| `getRuleResults` | | JMC's automated analysis findings, filtered by minimum severity. |
| `classifyBiggestIssue` | yes | Jev's pick of the dominant finding, with per-finding probabilities. |
| `classifyWorkloadProfile` | yes | Jev's likely/possible/unlikely judgment for each workload label. |
| `assessRuleResults` | yes | Jev's estimate, per rule, of how likely the problem a rule looks for is significant. |
| `getVersion` | | The server version. |

## What leaves your machine

Recordings are parsed and analyzed locally. Only the Jev-backed tools send anything over the
network: a summary of the recording to TypeSafe's API. That summary contains computed metrics
and JMC's rule results, but also class and method names from the hottest call paths, lock class
names, and whatever the rule results quote from the recording. Don't use the Jev-backed tools on
recordings whose code structure you are not allowed to share with TypeSafe.

The recording itself comes from the profiled application and is treated as untrusted: its
contents are only ever given to Jev as evidence to judge, never as instructions.

## Troubleshooting

- **"The JEV_KEY environment variable is not set"** - the key was not in the environment the
  assistant started the server in. Set it in the client's server configuration (`env` above), or
  export it and restart the client.
- **Anything else** - the server logs to `jmc-jev-mcp-server.log` in its working directory.
  Nothing is printed to the console, since the server talks to the assistant over stdout.

## More documentation

- [How it works](docs/how-it-works.md) - what is computed from a recording and sent to Jev.
- [Model choices](docs/model-choices.md) - why Jev, and why not the open Laya model for now.
- [Development](docs/development.md) - building, testing and releasing.

## License

[MIT](LICENSE)
