# Model choices

The judgments are made by [TypeSafe's Jev](https://typesafe.ai), a System One model: given a
state and typed questions, it returns calibrated probabilities rather than generated text, which
is what the tools need to turn findings into likely/possible/unlikely verdicts.

## Why not Laya?

[Laya](https://huggingface.co/convaiinnovations/laya) is an open-weights (Apache 2.0),
non-autoregressive System One model, and its `laya-serve` server speaks the same
`POST /v1/systemone` API as Jev, so it would be easy to plug in. It was evaluated and left out
for now, because it does not fit what these tools send:

- **Context.** The state sent to Jev is typically a few tens of KB of JSON - well over 10k tokens.
  Laya's English checkpoint reads 512 tokens and its multilingual one at most 8,192, shared with
  the question text, and `laya-serve` rejects states over 50,000 characters. Longer input is
  silently cut off at the end. Fitting would mean dropping most of the hot-path graphs, time series
  and histograms the questions are about.
- **Question length.** Questions get 192-256 tokens by default, less than several of the
  questions here need.
- **Zero-shot accuracy.** Laya's own model card puts the base checkpoints near chance on its
  typed-decisions benchmark; its headline numbers come from a checkpoint fine-tuned on that
  benchmark. It also reports that yes/no (noul) answers - which every `classifyWorkloadProfile`
  and `assessRuleResults` question is - can get stuck on a confident "no", and that probabilities
  are over-confident until calibrated on your own data.
- **Question count and latency.** `assessRuleResults` asks one question per JMC rule (74 with JMC
  9.1), more than the 64 per request `laya-serve` accepts. The state is also encoded once per
  question, so a near-8k-token state across that many questions loses most of the advertised
  speed advantage, especially on CPU.

Laya could become interesting as a base to fine-tune on labelled verdicts for this domain, fed a
much more compact summary of the recording.
