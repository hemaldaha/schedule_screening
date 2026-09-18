# Typo Fixes — Change Log

All changes are comment/string/parameter-name corrections only. No logic was altered.

| # | File | Line(s) | Before | After |
|---|---|---|---|---|
| 1 | `runner/ScreeningTrigger.java` | 28, 41 | field `procesror` and call `procesror.process()` | `processor` and `processor.process()` |
| 2 | `engine/pipeline/ChunkBuffer.java` | 39 | method `isPoisonpill(...)` | `isPoisonPill(...)` |
| 3 | `engine/pipeline/ChunkBuffer.java` | 43 | method `singalExhausted()` | `signalExhausted()` |
| 4 | `engine/pipeline/ChunkBuffer.java` | 47 | log string `[Chunkbuffer]` | `[ChunkBuffer]` |
| 5 | `engine/pipeline/ChunkBuffer.java` | 50 | log string `"signallling exhasusted"` | `"signalling exhausted"` |
| 6 | `engine/PipelineStageReporter.java` | 12 | parameter `stge` | `stage` |
| 7 | `engine/pipeline/AlertConsumer.java` | 85 | comment `timeoit` | `timeout` |
| 8 | `engine/pipeline/AlertConsumer.java` | 153 | comment `Evidance` | `Evidence` |
| 9 | `engine/ScoringConsumer.java` | 93 | comment `al candidates` | `all candidates` |
| 10 | `engine/ScoringConsumer.java` | 94 | comment `varientIndex` | `variantIndex` |
| 11 | `resources/application.yml` | 40 | YAML key `rolingpolicy` | `rollingpolicy` |

`mvn compile` completed with zero errors after all changes.
