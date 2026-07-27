# STM Contention Lab

**The counter moved once. The function ran five times.**

An interactive Clojure 1.12.5 simulator for the work hidden behind a correct
software transactional memory result. It makes automatic retries, discarded
speculative CPU, hot Ref sets, and unsafe side effects visible—and demonstrates
how [telemetry.sh](https://telemetry.sh) can connect one logical operation to
every transaction attempt.

## What it demonstrates

Clojure’s STM automatically retries a transaction when it conflicts. The final
Refs remain atomic, consistent, and isolated, but the entire `dosync` body can
execute again. Clojure’s reference documentation therefore warns against I/O
and other side effects inside transactions.

A commit counter tells you the state moved. It does not tell you how many
discarded functions ran before that commit, how much CPU they consumed, or
whether an external effect escaped from an attempt the STM later retried.

The model replays one logical counter workload through four strategies:

| Strategy | Speculative boundary | Constraint |
| --- | --- | --- |
| `alter` a hot Ref | Every operation performs a general read-modify-write against the same small Ref set | Correct general transaction semantics, with conflict retries |
| `commute` the counter | A commutative update may be applied against the latest value at commit | Valid only when the function is truly commutative and independent |
| Sharded Refs | Write points spread across multiple transactional locations | Aggregation and cross-shard invariants become more complex |
| Local batch + flush | Immutable local accumulation happens outside STM; one bounded batch enters a sharded transaction | Visibility is delayed and recovery needs batch identity |

With 16 workers, 120 operations per worker, one hot Ref, and 180 μs of
transaction work, the deterministic default model produces:

| Strategy | Attempts | Retries | Commit efficiency | Wasted work | Duplicate effects |
| --- | ---: | ---: | ---: | ---: | ---: |
| Alter the hot Ref | 5,677 | 3,757 | 33.8% | 676.3 ms | 939 |
| Commute the counter | 1,982 | 62 | 96.9% | 11.2 ms | 0 |
| Sharded Refs | 2,301 | 381 | 83.4% | 68.6 ms | 0 |
| Local batch flush | 105 | 9 | 91.4% | 4.0 ms | 0 |

The duplicate-effect estimate assumes 25% of logical operations perform an
effect and that the hot-Ref policy performs it inside the retried transaction.
The other strategies place the modeled effect after commit.

These are analytical results, not a benchmark of Clojure’s STM implementation.
Use the lab to form a hypothesis, then validate a real service with transaction,
runtime, and effect telemetry.

## Run it

Requirements: Java 8+ and the Clojure CLI. The project pins Clojure 1.12.5 and
has no application dependencies.

```sh
make check
make run
```

Open <http://127.0.0.1:8080>.

Or use the exact official Clojure tools image:

```sh
docker build -t stm-contention-lab .
docker run --rm -p 8080:8080 stm-contention-lab
```

The container uses the Clojure CLI `1.12.5.1654` with Temurin 21 on Alpine and
runs as the unprivileged `10001:10001` user.

## API and CLI

The browser calls:

```sh
curl 'http://127.0.0.1:8080/api/simulate?workers=16&hot_refs=1&shards=16'
```

Integer query parameters are normalized to safe model ranges:

- `workers`
- `operations_per_worker`
- `hot_refs`
- `critical_work_us`
- `side_effect_percent`
- `shards`
- `batch_size`
- `observation_seconds`
- `seed`

Print the default result without starting a server:

```sh
clojure -M:json
```

## Telemetry recipe

Keep a stable logical transaction ID across retries and assign a monotonic
attempt number to each execution:

```text
stm.transaction.id
stm.transaction.attempt
stm.transaction.outcome
stm.transaction.retry_count
stm.transaction.duration_us
stm.ref.key_hash
stm.speculative.cpu_ms
stm.effect.phase
stm.commit.efficiency
```

Useful investigation sequence:

1. Compare total transaction attempts with successful commits.
2. Group retries by privacy-safe Ref fingerprint and deployment cohort.
3. Overlay conflict retries, transaction p99, runnable threads, and CPU.
4. Inspect spans or events emitted from attempts that did not commit.
5. Move external work after commit or put it behind an idempotent boundary.
6. Confirm that `commute` is semantically valid before using its lower-conflict
   behavior as a performance fix.

The logical transaction, attempt, and commit are separate telemetry events.
Collapsing them into one span hides precisely the work this lab is meant to
expose.

## Model mechanics

The simulator estimates a conflict probability from worker concurrency, hot
Ref count, and transaction duration. Policy semantics then alter the effective
write-point overlap, transaction count, and side-effect boundary. All jitter
and trace samples are deterministic for the configured seed.

Intentional simplifications:

- writers are modeled as a steady cohort rather than a real scheduler;
- conflict probability is analytical and capped, not measured from Clojure;
- `commute` is treated as valid for a pure counter update;
- shard selection is uniform;
- local batches flush at their configured maximum size;
- transaction CPU excludes allocation, garbage collection, and cache effects;
- duplicate effects are comparative estimates, not delivery guarantees.

## References

- [Clojure Refs and Transactions](https://clojure.org/reference/refs)
- [Clojure 1.12.5 downloads and Java compatibility](https://clojure.org/releases/downloads)
- [Clojure CLI tools releases](https://clojure.org/releases/tools)
- [Clojure Docker Official Image](https://hub.docker.com/_/clojure)

## Stack

- Clojure 1.12.5 for the deterministic model, JSON serialization, API, and tests
- Java’s built-in `HttpServer`; no web framework
- semantic HTML, modern CSS, vanilla JavaScript, and Canvas 2D
- strict query normalization, security headers, and deterministic fixtures
- exact non-root Clojure/Temurin Alpine image and two-lane GitHub Actions CI

## License

[MIT](LICENSE)
