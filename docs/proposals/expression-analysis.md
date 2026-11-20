# Coeffect and Effect Analysis for Iceberg Expressions

*A proposal to the Iceberg UDF sync concerning which expressions each specification feature admits and the conditions under which consumers may rely on them.*

**Author:** Alex Cruise  
**Updated:** November 20, 2026  
**Status:** draft for discussion; specification text is quoted from apache/iceberg `main` at commit `037d8af`

## Summary

Version 4 of the table specification admits expressions in write defaults, `check` constraints and statistics over expressions, and proposals in flight add them to index definitions and to the bodies of user-defined functions. So far, every feature that uses expressions has defined its own rule about which expressions it accepts, and the UDF specification summarizes a function's behavior in one flag, `deterministic`, which is too coarse to serve any of them. What a feature needs to know is what an expression reads from its context (its *coeffects*: the clock, the session's time zone, the engine's Unicode tables and regular expression dialect, other tables, the order of evaluation) and what evaluating it can do (its *effects*: produce a null, raise an error), and different features tolerate different subsets of both. The terms are taken from the programming languages literature and are not essential to the proposal, which concerns the analysis they name.

This note asks for four things:

1. That the [expressions specification](https://github.com/apache/iceberg/blob/037d8af/format/expressions-spec.md) define a vocabulary of coeffects and effects, rules for computing them over a whole expression tree, and a way to record with a stored expression the values of the context it reads.
2. That every function the specifications define, and every user-defined function, declare those properties, with an undeclared property taken to be the worst case.
3. That every consumer analyze every node of an expression and refuse one containing anything it cannot analyze, with the consequence of refusal (failing, or ignoring the expression) fixed by the feature that carries it.
4. That every feature that uses expressions specify which properties it permits, what a consumer may rely on an expression for when it passes, and what the consumer does with one it refuses.

The urgency comes from a change of role. Expressions entered Iceberg as things writers evaluate, where a misunderstanding fails a write, and increasingly serve as hints that planners trust, where a misunderstanding produces an incorrect query result. The changes fall into [three phases](#9-changes-in-three-phases), of which the first needs almost no new metadata and should land before planners begin to rely on stored expressions.

## 1. Expressions in version 4

The expressions specification describes itself as deliberately simple, with complexity "pushed to the functions that are called", which include user-defined functions "that can use the full range of SQL capabilities". A node of that simple structure can therefore hold any function defined by a catalog. So far, every feature that uses expressions has defined its own rule:

| Where | What the table specification says | What it leaves open |
|---|---|---|
| [`write-default`](https://github.com/apache/iceberg/blob/037d8af/format/spec.md#default-values) | Field references are not allowed; constants and function applications are | When and how often the expression is evaluated, and whether a retried write evaluates it again |
| `initial-default` | Must always be a literal | Nothing, at the price of admitting no expressions at all |
| [`check` constraints](https://github.com/apache/iceberg/blob/037d8af/format/spec.md#check-constraint-expressions) | Must be "pure", its result depending "only on the values of the fields it references in the row"; a UDF used in one "must declare `deterministic` as true" | Who verifies the declaration, and what a redefined UDF means for a constraint already validated |
| [`expr-value` stats-only fields](https://github.com/apache/iceberg/blob/037d8af/format/spec.md#stats-only-fields) | Must use only ID references; a writer that does not support an expression sets its stats to null | Whether a reader evaluates the expression as the writer did |
| Scan planning filters and residuals in the REST protocol | Named references | Which functions a filter may call, and whether client and service agree about them |

Proposals in flight add input expressions on index blobs ([Puffin proposal, Section 7.8](puffin-blob-types-and-compaction.md#78-expressions-as-inputs)), derived columns in the index specification, and expressions in UDF bodies. Each rule answers, for one feature, a question that none of them asks in general: what does this expression depend on, and what can happen when it is evaluated? The `check` rule comes closest, since depending "only on the values of the fields it references" is a statement about what an expression reads, and having no side effects is one about what it can do.

## 2. Determinism and other coeffects

The [UDF specification](https://github.com/apache/iceberg/blob/037d8af/format/udf-spec.md) gives each definition version an optional `deterministic` flag, defaulting to false. One bit has to stand for several distinct dependencies, which matter to different consumers in different ways:

- *The current time.* `current_timestamp()`, or `now()`, is deterministic given the time, and whether that is acceptable depends on where it appears:
  - As a `write-default` it is acceptable, since the stored value is what every reader sees, although the specification does not say whether a writer evaluates it per row or per statement, or again when a task is retried.
  - As an `initial-default` it is useless, since readers would evaluate it retroactively and disagree.
  - In a query, engines bind it once at the start of execution, which treats the current time as an environment parameter like the session's time zone. Since each run binds it afresh, a query against an immutable snapshot returns a different answer on every run.
  - In a materialized view the dependency is often intended, as in a view over the last seven days, but the second example freshness criterion of the [draft materialized view specification](https://github.com/apache/iceberg/pull/11041) judges such a view fresh because no recorded source snapshot has moved.
- *Time zones.* A function of a local date or time reads the session's time zone, and one that converts between zones reads the implementation's time zone database, whose rules change several times a year. British Columbia shows both dependencies:
  - *The database release.* The province moved to permanent UTC−7 on 9 March 2026, a change first recorded in time zone database release `2026b` of 22 April 2026. An engine with an earlier release returned `America/Vancouver` to UTC−8 on 1 November 2026, and since then has placed events in the first hour after local midnight on the previous day.
  - *The session time zone.* Vancouver and the San Francisco Bay Area now share a UTC offset in summer and differ by an hour in winter. Two sessions there agree on local dates only while the Bay Area observes daylight time, and which case holds changes at each of its two clock changes a year.
- *Unicode.* `upper()` and `lower()` over non-ASCII text read the implementation's Unicode tables, so two engines on different Unicode versions can disagree although each is deterministic in isolation.
- *Regular expressions.* `regex_match()` reads a dialect, and dialects disagree about backreferences, lookaround and what `.` matches outside ASCII.
- *Floating point.* IEEE 754 rounds each basic operation correctly, but addition over doubles is not associative, so a sum depends on the order of its additions. Kahan's compensated summation recovers most of the precision a naive running sum loses by carrying the rounding error of each addition into the next, and a compiler that assumes associativity simplifies its compensation term to zero. Transcendental functions such as `exp` and `log` also read the math library, since the standard recommends correct rounding for them without requiring it. An incremental refresh that adds new rows into a materialized view's stored `sum` over doubles can therefore store a different answer from a full refresh.
- *Entropy.* `random()`, or a generated UUID, reads a source of entropy, and is the case to which the term "nondeterministic" most properly applies.
- *Seeded generators.* `random(seed=4)` reads no entropy, and with a fresh generator for each call it would be a constant. What it does read depends on how the engine carries the seed to each row:
  - *A single sequence.* An engine that creates a generator once, commonly per task, and draws from it row by row makes the seed an environment parameter that initializes a state, together with a generator algorithm that differs between engines. Each row's value depends on its position in the sequence, which follows from how the work was split and the order in which rows were visited, so a different split or a retried task assigns the same values to different rows. Spark's `rand(seed)` [seeds each partition's generator](https://github.com/apache/spark/blob/069741eca5ff/sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/randomExpressions.scala#L63-L65) with the seed plus the partition index, and its documentation notes that "the function is non-deterministic in general case".
  - *A split generator.* Splitting, as Java's `SplittableRandom` and counter-based generators such as Philox allow, derives a stream for each task or row from the seed and a key. The values are then independent of scheduling only if the keys are, and only if the splits are made in a consistent order. A task's partition index still depends on how the input was divided, and a row's position in its file changes when compaction rewrites the file.
  - *A stable key.* Row lineage in version 3 gives each row an ID, and the specification directs a rewrite that moves a row to another file to copy the ID with it, so the ID survives compaction. A counter-based generator keyed by the seed and the row ID makes each value a function of the row, within two limits. The ID is assigned only when the snapshot adding the row commits, and reassigned if the commit is retried, so a write default cannot use it. And an update can give the row a new ID, and with it a new value: the specification says that lineage "does not track lineage for rows updated via Equality Deletes", and allows other engines to model an update as a delete and an insert.
- *Other tables.* A lookup reads state outside the row, which changes independently of the expression.

A single bit cannot represent these distinctions without misclassifying some function. Marking `current_timestamp()` nondeterministic excludes it from defaults whose meaning is well defined; marking `upper()` deterministic admits it into a stored index expression that a reader on another Unicode version evaluates differently, and an index built under different case-folding rules from the query's excludes files that contain the rows sought. What most stored expressions need is *reproducibility across evaluators*, the same answer from every implementation that evaluates the expression later, of which determinism is the special case with a single evaluator.

### 2.1 Effects

Effects need the same treatment, and the specifications already reason about some of them piecemeal:

- The expressions specification gives nulls from `get_item` and NaNs from `multiply` a fixed meaning through null-safe comparison.
- A writer must fail when a required field's `write-default` evaluates to null.
- The UDF specification's `return-nullable` and `on-null-input` declare null behavior, and `on-null-input: return-null` licenses an engine to rewrite `WHERE f(a,b) > 0` as `WHERE a IS NOT NULL AND b IS NOT NULL AND f(a,b) > 0`.
- `secure` asks engines to "prevent leakage of sensitive information", which constrains what an error may reveal.

The specifications do not treat errors in general. Division by zero in a `write-default` fails the write, and the specification does not say whether, in a `check` constraint, it is a violation.

### 2.2 A starting vocabulary

Offered for the sake of argument, and expected to change:

| Coeffects: what evaluation reads | Effects: what evaluation can do |
|---|---|
| Fields of the current row, by ID | Produce a null |
| Rows other than the current one, or other tables | Raise an error (and which kinds) |
| The current time, at a granularity: a day, an hour, the engine's finest resolution | Fail to terminate, or exceed a resource bound |
| Session context: time zone, locale, user | Produce a NaN |
| Implementation tables: Unicode, time zone database, regular expression dialect, math library | Reveal what it read through an error or timing |
| A source of entropy | Write anything outside its result |
| Evaluation state: how many times, and in what order, it has been evaluated or its inputs combined | |
| The definition of a function it calls, at a particular version | |

## 3. Analysis over the expression tree

Expressions nest without limit, so a rule that inspects only the outermost function is insufficient: `upper(trim(coalesce(my_udf(), 'n/a')))` reads the clock if `my_udf` does. Properties are computed over the abstract structure the expressions specification defines, which makes them the same whether the expression arrived in the JSON serialization or in the REST protocol's deprecated term encoding:

1. A constant reads nothing and does nothing.
2. A field reference reads that field of the current row.
3. An application reads whatever its function reads, together with whatever each of its arguments reads, and can do whatever its function can do together with whatever each of its arguments can do, less what the function discharges.
4. A function may declare that it discharges an effect of some of its arguments. `coalesce` discharges the possibility of a null in its earlier arguments if its last argument cannot be null, and `if_else` discharges the effects of the branch it does not take only if the specification guarantees that it does not evaluate that branch.
5. A function without a declaration is analyzed as if it read everything and could do everything. The function need not actually do so; a consumer that cannot know what the function reads or does must make the assumption to be safe.

### 3.1 Graded coeffects: the current time

Some coeffects carry a parameter, and combining them needs a rule of its own. `current_date` reads the time at the granularity of a day, in the session's time zone; `now()` reads it at the engine's finest resolution; and `date_trunc('hour', now())` reads it at an hour, because truncation discharges the finer granularity of its argument under rule 4. The granularity bounds how long a result may be memoized, so an expression that reads only `current_date` holds until midnight. An expression that reads the time in several places holds until the earliest of their next boundaries, which is the boundary of the shortest granularity when the granularities nest, as hours do within days in one time zone. When they do not nest, as with weeks and months, or days in two time zones, the combination is the union of their boundaries. (The coeffects literature calls a parameter of this kind a grade.) A materialized view is the consumer that most needs this, since the combined granularity tells an engine when the view goes stale although no source has changed.

### 3.2 The predicate boundary

The expressions specification already bounds one effect, the production of a null, by distinguishing predicates from value expressions. A predicate must produce true or false, and a value expression is not a valid predicate even when it returns a boolean: `is_empty(str_col)` is rejected "because it may produce `null`", and `is_empty(str_col) = true` is accepted. Since predicates use 2-valued logic, an engine with SQL's 3-valued logic settles what a null means before it passes an expression on, "rather than depending on context to interpret a null result":

| SQL condition | Passed for a `WHERE` clause | Passed for a `CHECK` constraint |
|---|---|---|
| `x < 10` | `x < 10 AND x IS NOT NULL` | `x < 10 OR x IS NULL` |

The boundary thus discharges the effect of producing a null, conservatively, and the rewrite settles at write time a dependency on which feature will consume the expression, which is the move Section 4 proposes for environmental defaults. It covers nulls and NaNs only. A comparison whose operand divides by zero raises an error, which is neither true nor false, and the specification does not say what it means.

### 3.3 Rewrites and algebraic laws

The tree also fixes the order of evaluation. An engine that rewrites it, whether to fold constants, to regroup operands for a vectorized kernel or to match a query's term against a stored expression, may use only identities that hold for the functions involved. Over doubles, `(a + b) + c` and `a + (b + c)` are different expressions, and over integers they are the same only if overflow wraps, since under ANSI mode one grouping can raise an error where the other does not. Which laws a function obeys is a property it can declare, as `on-null-input` declares a law about nulls, and a rewrite that relies on an undeclared law treats an unanalyzed function as if its properties were known.

### 3.4 UDF bodies and versions

The analysis divides the work between two specifications. Write defaults, `check` constraints, `expr-value` statistics, index input expressions and scan filters store trees in the structure the expressions specification defines, and the rules at the start of Section 3 run over those trees. The UDF specification stores a function's body as SQL text in one or more dialects, which the Iceberg specifications do not parse and should not have to, since each dialect belongs to an engine. A call to a UDF is therefore a leaf of the tree, whose declarations stand in for its body, and the UDF specification's obligation is limited to defining those declarations and saying who may rely on them (Section 5).

Since the analysis cannot see into a body, the UDF's declarations must cover the body and everything it calls. They must also hold for every representation: a function whose Spark and Trino bodies are each deterministic but disagree with each other is not reproducible across evaluators (Section 7, example 4). An engine able to parse a dialect may check the declarations against the body, and should refuse a definition whose body reads or does more than it declares.

Since definitions are versioned, a stored expression should record the definition versions it was analyzed against, or be analyzed again when they change. The table specification declines this for `check` constraints, whose UDFs are "expected to preserve" their behavior across definition versions while readers and writers "are not required to detect behavioral changes". That is tolerable only while no planner relies on a constraint's status (Section 6).

## 4. Recorded context

Many coeffects are dependencies on an environment that is fixed for the duration of a query and differs between engines: the collation, the regular expression dialect, the Unicode version, the time zone database release, the session time zone, and switches such as ANSI mode. An expression that reads one of these is underspecified, since its text omits values on which its result depends. Stored together with the values it was written against, as implicit parameters, it carries its environment with it. (Implicit parameters are among the motivating examples in Petricek, Orchard and Mycroft's 2013 paper that introduced coeffects.)

A consumer can then rely on the expression if it can evaluate it under the recorded context, either because its own environment matches or because it can construct one that does, as an engine can usually switch ANSI mode on request or bundle a library for a named regular expression dialect. A consumer that can do neither refuses the expression (Section 6). Reproducibility across evaluators thereby becomes a relation between the record and the evaluator, and a feature's test changes from "reads no implementation tables" to "reads only what it records". `upper()` over non-ASCII text, which the first test excludes from every stored use, passes the second wherever the Unicode version is recorded, and a reader with other tables ignores an index built on it.

Four conditions make this work:

1. **The recorded values have names that implementations agree on**: a Unicode version, an IANA time zone database release such as `2026b`, a collation as a locale tag with the version of its rules, a dialect or mode by a name the expressions specification registers. A consumer can compare names but cannot check that its environment behaves as a name claims, so the record is trusted as a declaration is (Section 5).
2. **The record holds the values in effect when the expression was declared, defaults included.** An omitted optional argument is an implicit parameter whose value the declaring engine supplied. A geospatial distance function whose optional argument chooses planar, spherical or spheroidal computation means, without the argument, whatever its author's engine defaulted to, and another engine with another default computes a different distance from the same text. Session settings are usually defaults as well. Resolving every default at declaration lets a stored expression keep its meaning when an engine or a specification later changes one.
3. **An expression that records nothing is analyzed as if it read an unknown environment**, the worst case under rule 5. Functions whose behavior the specification fixes need no record, since the specification is their environment.
4. **Only some coeffects can be recorded usefully.** Another table can be recorded as a snapshot ID, which is what a materialized view's refresh state does, and a called function as its definition version. The current time can be recorded as the instant a query bound, which makes a replay repeatable but cannot make a stored result current, since freshness is a question about the present. Entropy cannot be recorded. A seed and a generator algorithm can be, but they fix each row's value only when the generator is keyed by something stable, such as a row ID (Section 2); otherwise they fix only the start of a sequence whose position at each row depends on the schedule.

The first condition is weaker than it appears, because the meaning of a name can change between releases of the table it names, and a record that holds only the name cannot show that the writer and the reader resolved it differently. Trino's `timestamp with time zone` illustrates the gap. Its value [packs](https://github.com/trinodb/trino/blob/8ccdf08a5c32/core/trino-spi/src/main/java/io/trino/spi/type/DateTimeEncoding.java#L43-L48) an instant in UTC milliseconds with a key for a zone ID or a fixed offset (above millisecond precision, a separate structure holds the same key), and for a named zone neither form keeps the offset that was in force. A value written in Vancouver on 1 December 2026 at 10:00 local time, under release `2026b`, holds the instant and `America/Vancouver`. A reader with an earlier release renders it as 09:00 and has no means of detecting that the writer saw 10:00.

A timestamp in the format of RFC 9557, such as `2026-12-01T10:00:00-07:00[America/Vancouver]`, carries both the offset and the zone, so a reader whose rules give the zone another offset at that instant can detect the inconsistency. A recorded context gains the same check by holding the resolved value beside the name, wherever the value is small enough to store.

## 5. Declarations as promises and as requirements

Read as a promise, a declaration is a claim by its author about the thing declared, such as how a function's body behaves, and a consumer that relies on it trusts the author. Read as a requirement, it is an instruction to whoever evaluates the expression, and a consumer needs only the ability to comply. The UDF specification uses both readings without saying which applies, sometimes within one field:

- `on-null-input` is described as "an optimization hint". Its value `return-null` is phrased as a promise, that "the function always returns `NULL` if any input argument is `NULL`", and its value `call` as a requirement, that "the engine must execute the function".
- `secure` is a requirement on engines, resting on a promise by the administrator, whose "responsibility" it is that secure functions are properly configured.
- `deterministic` is a bare statement of fact, which the table specification requires in `check` constraints without saying who verifies it.

The first of these already allows conforming engines to disagree. If a function declared `return-null` maps a null input to something else through `coalesce`, an engine that reads the declaration as a requirement returns null without calling the body, while one that reads it as a hint calls the body and returns the other value.

The two readings differ in what a consumer does with a declaration it does not understand. A promise is a license, which a consumer may ignore at the cost of an optimization; a requirement is an obligation, and a consumer that cannot meet it must refuse. Many declarations are both. A recorded context requires the evaluator to use a particular Unicode version and promises that the stored values were computed under it, and a materialized view's declared time granularity promises when the result can change while the refresh's binding of the current time is required of the refreshing engine. Only promises raise a question of trust, which is settled either by a consumer that checks the promise, as an engine that parses a body's dialect can, or by a rule assigning responsibility for a false one. The specification should say of every declaration which reading it intends, and of every promise whether it may change a query's results or only save work.

Rule 5 makes an author's ignorance safe. An author who declares nothing about a function loses optimizations, never correctness, so the harm comes only from a declaration that is made and is false. Such declarations are most likely where reliance changes results, because a feature that admits functions by their declarations gives authors a reason to make them.

PostgreSQL is the precedent. It requires every function in an index definition to be immutable, meaning that "their results must depend only on their arguments and never on any outside influence", and its [documentation of `CREATE INDEX`](https://www.postgresql.org/docs/current/sql-createindex.html) advises: "To use a user-defined function in an index expression or `WHERE` clause, remember to mark the function immutable when you create it." It does not check the mark, beyond forbidding SQL commands other than `SELECT` in the body, and its [documentation of function volatility](https://www.postgresql.org/docs/current/xfunc-volatility.html) names the usual mistake: "A common error is to label a function `IMMUTABLE` when its results depend on a configuration parameter", such as a function of timestamps that reads the `TimeZone` setting. That is the dependency behind examples 3 and 5 of Section 7.

A consumer can therefore weigh a promise by its source as well as by the stakes. Four sources can be distinguished:

- *The specification*, for the functions in `iceberg_functions` and those in `sql_functions` that Iceberg defines exactly, which bind every conforming implementation.
- *An engine*, which parsed the body's dialect when the definition was created and inferred or checked the declarations, and which the definition version records by name and version.
- *An administrator*, who attests to the declaration as `secure` already requires.
- *The author*, for every other declaration.

A promise that only saves work can be accepted from any source. A promise on which a result depends, such as one a planner uses to exclude files or to remove a predicate, could be required to come from one of the first three, with each feature's admission rules (Section 8) saying which. Most expressions stored in indexes and statistics are likely to call only functions the specification defines, so the restriction falls mainly on UDFs.

## 6. Refusal of unanalyzable expressions

A consumer that encounters a function it does not know can either treat the function as harmless or refuse the expression, and the first course is the source of undetected incorrect results. This note proposes refusal, stated formally as assuming the worst: an unknown function is analyzed as if it had every coeffect and every effect, so that it fails every test a feature imposes and the consumer takes the course the feature prescribes. The obligation falls on readers as well as writers, because the cost of a misunderstood expression usually lands on a reader. The course depends on how the consumer relies on the expression, and three cases recur:

1. **Metadata a consumer may ignore.** A planner that cannot analyze the expression of an `expr-value` stats-only field does not use its statistics, and reads files it might have skipped. The stats-only field rule already says readers must not fail on an unsupported field type, and the same tolerance extends to an unsupported expression.
2. **Values a consumer must produce.** A writer that cannot evaluate a `write-default` cannot supply the field's value, and must refuse the write instead of writing a null, which the specification does not yet say.
3. **Hints a planner trusts.** A planner that matches a query's term to an index blob's input expression, or to an `expr-value` field's expression, concludes from statistics over the stored expression that some files cannot contain matching rows. If it resolves a function in the stored expression to a different definition, because the version or the dialect differs, it excludes files that contain matching rows. A planner that eliminates a predicate because a `check` constraint over it is `validated` is in the same position once a UDF the constraint calls is redefined.

The third case is what makes the analysis urgent, and it is the polarity distinction of the [note on contracts](../contracts/README.md#appendix-b-dimensions-of-the-contract): a structure that only licenses a reader to skip work is safe to ignore and safe to misjudge conservatively, and one that licenses a conclusion about what the data contains needs an analysis that is exact, and reproducible across evaluators, before a planner relies on it.

## 7. Hazards of faulty declarations, by feature

This section gives one example per feature of a declaration that is missing or wrong, and of the incorrect result that follows. A declaration is *missing* when the specifications give no way to state a property, or when a consumer treats an undeclared property as absent, which rule 5 forbids and which the specifications cannot yet avoid, since they lack the vocabulary. A declaration is *wrong* when its author states something false. Several of the wrong declarations below could be made in good faith, because each is true under some reading of the flag.

Consumers rely on an expression in two directions, which are the assertion polarities of the [note on contracts](../contracts/README.md#appendix-b-dimensions-of-the-contract) applied to a consumer's use of an expression:

- **Negative reliance.** The consumer concludes from the expression that rows are absent, and skips a file, a row group or a whole query. A faulty declaration loses rows.
- **Positive reliance.** The consumer uses the expression in place of evaluating something, taking a stored value, a stored result or a proof that every row satisfies a condition as true. A faulty declaration returns rows that do not match, or wrong values.

Reliance also differs in when it occurs. In *planning*, a consumer draws a conclusion from metadata (statistics, an index, a constraint's status, a view's refresh state) without evaluating the expression on the data. In *execution*, an engine evaluates the expression or declines to, and a writer's result becomes data that every later reader takes as given.

| Feature | Who relies, and when | Polarity | Declaration at fault | Result |
|---|---|---|---|---|
| `write-default` | Every reader of the row, after the write | Positive | Wrong: `deterministic` on a function that reads entropy | One identifier stored for many rows |
| `check` constraint | Planner | Both | Wrong across time: `deterministic` on a function that reads another table | Extra rows, or none |
| `expr-value` statistics | Planner | Negative | Missing: a cast reads the session time zone | Lost rows |
| Index input expression | Planner | Negative; positive for an exact index | Missing: a UDF's representations differ by dialect | No rows |
| Scan planning filter | Service in planning, client in execution | Both | Missing: a cast reads the session time zone | Rows from the wrong day, and lost rows |
| UDF null behavior | Planner, and executor | Negative in planning, positive in execution | Wrong: `on-null-input: return-null` | Lost rows, and nulls in place of values |
| Materialized view | Engine substituting the stored result | Positive | Missing: the query reads the current time | Expired rows |

`initial-default` has no entry, since it admits only literals.

1. *Write default.* A table of support tickets declares `write-default: new_ticket_id()`, a UDF whose body calls `uuid()`. Its author marked it `deterministic` because it has no side effects. A `write-default` may not reference fields, so a deterministic default takes nothing from the row, and an engine is entitled to evaluate it once per statement and reuse the value. An insert of 10,000 tickets then stores one identifier 10,000 times. The reliance is positive and begins at execution: every reader takes the stored value as the field's value, so the duplicate is data from then on, and a later `MERGE` keyed on the identifier updates all 10,000 rows. The declaration omits entropy.
2. *Check constraint.* `is_business_day(trade_date)` reads a table of holidays, and its author declared it `deterministic`, which is true within one query. A constraint over it is validated, and a holiday is later added for 24 December 2026, a date on which trades have already been written. A planner that removes `WHERE is_business_day(trade_date)` as implied by the constraint returns those trades (positive reliance, extra rows). A planner that concludes from the same constraint that `WHERE NOT is_business_day(trade_date)` cannot match returns nothing where it should return them (negative reliance, lost rows). Both errors occur in planning, and the writer's enforcement was correct when it ran. Recording definition versions (Section 9, item 3) does not detect the change, since the definition did not change and the table it reads did.
3. *Statistics over an expression.* An `expr-value` field records bounds for `cast(event_ts AS date)`, where `event_ts` is a `timestamptz`. The cast reads the session time zone, and nothing declares this. A writer in UTC writes a file of events from 00:00 to 07:00 UTC on 20 November 2026, and records 2026-11-20 as both bounds. A reader in Los Angeles, eight hours behind UTC in November, queries `cast(event_ts AS date) = DATE '2026-11-19'`, which matches the stored expression's text, and skips the file, although every event in it occurred on the 19th in Los Angeles. This is negative reliance in planning. With the time zone recorded (Section 4), the reader would see that the bounds were computed in another zone and ignore them.
4. *Index input expression.* A UDF `normalize_phone` has Spark and Trino representations, both declared `deterministic`, and each is. The Spark body keeps only digits; the Trino body, written later by someone else, also drops a leading country code. A compaction job in Spark builds a Bloom filter over `normalize_phone(phone)`, which holds `15550100` for the number +1 555 0100. A Trino query for that number probes for `5550100`, which no filter contains, and returns no rows (negative reliance, planning). The result depends on which representation the engine chooses, and `deterministic` makes no claim across representations. An exact index used without rechecking the predicate would instead return rows that do not match as the querying engine evaluates it (positive reliance).
5. *Scan planning filter.* A client in Tokyo, nine hours ahead of UTC, sends `cast(event_ts AS date) = DATE '2026-11-19'` to a planning service that evaluates in UTC. The table is partitioned by `day(event_ts)`, also in UTC, so the service selects the partition for 19 November and returns its files with the residual `true`, and the client returns their rows unfiltered. Those rows run from 09:00 on the 19th to 09:00 on the 20th in Tokyo:
   - The result includes nine hours of the 20th (positive reliance on the residual, execution).
   - It lacks the first nine hours of the 19th, which lie in the partition the service excluded (negative reliance, planning).

   Section 8 requires the client to resolve session context, which it cannot do without a declaration that the cast reads it.
6. *UDF null behavior.* `effective_price(discount, price)` returns `price - coalesce(discount, 0)`, and its author copied `on-null-input: return-null` from another definition. A planner that reads the declaration as a law rewrites `WHERE effective_price(discount, price) > 0` to require `discount IS NOT NULL`. Where null counts show that a file has no discounts at all, the planner skips the file and loses every undiscounted sale in it (negative reliance, planning). An engine that reads the declaration as a requirement returns null for those rows in a projection without calling the body (positive reliance on the declaration in place of evaluation, execution). An engine that reads it as a hint calls the body and returns the correct prices, so the same query returns different results on different engines, as Section 5 describes.
7. *Materialized view.* A view selects orders `WHERE order_ts > now() - INTERVAL '7' DAY`, and nothing declares that it reads the current time. Over a long holiday weekend no orders arrive, the source snapshot recorded in the refresh state remains current, and the view is judged fresh. An engine that substitutes the stored result for the query returns orders up to ten days old. This is positive reliance in planning. A declared granularity (Section 3.1) would tell the engine that the stored result expires at the next boundary, although no source has changed.

In none of these examples does a component fail or report an error. Each acts correctly on the declaration it is given, and the incorrect rows reach the user as an ordinary result. Lost rows are the harder to notice, since nothing in the result points to them. This is why Section 6 places the obligation to refuse on the consumer, and why the admission rules in Section 8 state, for each feature, what a consumer may rely on.

## 8. Admission rules for features

With the analysis defined centrally, a feature states its requirements as tests of computed properties, together with what a consumer may rely on an expression for and what it does on refusal. For the features above, these might read:

| Feature | May read | May do | What a consumer may rely on it for | A consumer that refuses the expression |
|---|---|---|---|---|
| `write-default` | The current time and session context, if the feature pins which context, and when and how often the expression is evaluated | Produce a null only for an optional field; raise an error, which fails the write | Readers take the stored value as the field's value and never evaluate the expression again | Fails the write |
| `initial-default` | Nothing, after constant folding by the commit that adds the field | Nothing | Every reader substitutes the same value for the field in rows written before it existed | Fails the schema change |
| `check` constraint | Fields of the current row; implementation tables and session settings only as recorded | Produce a null or a NaN, which null-safe comparison already interprets; an error needs a stated meaning | A writer rejects rows that fail it; a planner eliminates a predicate implied by a `validated` constraint only if the definition versions it was validated against are current | A writer fails the write; a planner does not use the constraint |
| `expr-value` stats, index input expressions | Fields of the current row; implementation tables and session settings only as recorded | Produce a null; raise an error, if the stats or index record which rows erred | A planner skips files the statistics or index show cannot match, for a query term that matches the stored expression exactly under its recorded context | Ignores the statistics or the blob |
| Scan planning filter | Fields of the current row; session context, resolved by the client | Anything the engine can evaluate | A client reads only the files the service plans, and applies the residual the service returns, or computes its own where the service omits it | Evaluates the residual itself, or fails the query |

The alternative is an allowlist per feature, and an allowlist is never complete. A list sized for current use admits `upper` and `trim`, and subsequent uses require `concat`, then `substring`, then division, then `regex_match`, then `contains`, and each addition is a specification change argued afresh in each feature's section, with the risk that two features admit the same function under different conditions. Under the analysis, a function is admitted as soon as it declares its properties, in every feature whose tests those properties pass. `regex_match`, declared as reading its dialect, is admitted in a scan filter, where a single engine evaluates it, and in a stored index expression only with its dialect recorded, and neither rule needs to be written separately.

An allowlist's one advantage is that its members' behavior is fixed by the specification and therefore portable, and the two approaches combine. The functions in `iceberg_functions`, and those in `sql_functions` that Iceberg chooses to define exactly, form a floor that every conforming implementation evaluates identically; beyond the floor, functions describe themselves, and the analysis decides where each may go. This is the arrangement the [Puffin proposal](puffin-blob-types-and-compaction.md) makes for blob types, a small set of standard types beside an open namespace of self-describing ones.

## 9. Changes, in three phases

Each phase is useful without the ones after it, and the items are numbered across phases so that they can be cited.

**Phase 1: fence what already exists.** These changes need no vocabulary and almost no new metadata, and they remove the sources of incorrect results before planners begin to rely on stored expressions.

1. In the expressions specification, a requirement that a consumer refuse an expression containing a function it does not know, before evaluating, storing or trusting it, and take the course the feature carrying the expression prescribes.
2. In the table specification, for each existing feature, what a consumer may rely on the expression for and what it does on refusal, including when and how often a `write-default` is evaluated ([prose fix 13](../contracts/prose-fixes.md)).
3. For `check` constraints, a decision on whether a planner may rely on a constraint's status, and if so, a requirement that the status record the definition versions of the UDFs its expression calls.
4. In the UDF specification, a statement that engines may rely on a declaration to change a query's results only if it is part of the definition version the expression was analyzed against, and a statement of whether `on-null-input: return-null` obliges an engine to return null or only licenses it to.

**Phase 2: a vocabulary, and declarations in it.** These let a feature admit a function by its properties instead of by name.

5. In the expressions specification, a section defining the property vocabulary, the computation over expression trees, and the rule that an undeclared function is analyzed as if it had every property.
6. Declared properties for every function in `iceberg_functions`, and for whichever `sql_functions` Iceberg defines, including the algebraic laws that license an engine to rewrite an expression.
7. In the UDF specification, property declarations on each definition version, each stated as a promise, a requirement or both, of which `deterministic`, `return-nullable` and `on-null-input` become special cases or shorthands.
8. The rules of item 2 restated as tests of computed properties.

**Phase 3: recorded context.** This admits functions that depend on their environment into stored uses, for consumers that can reproduce the environment.

9. In the expressions specification, a way to record with a stored expression the values of the environmental coeffects it reads, with every default resolved, and a registry of names for those values.
10. For features that store expressions and are still being designed, such as index input expressions and a materialized view's [declared context dependencies](../contracts/materialized-views.md#candidate-text), recorded context from the start, since adding it later strands whatever was stored without it.

## 10. Open questions

1. **Which sources of a promise suffice for reliance that changes results?** An engine that cannot parse a UDF's dialect must take its author's promises on faith or decline to rely on them where reliance changes answers. Is the set of acceptable sources (Section 5) fixed once in the UDF specification, or by each feature's admission rules? And when a consumer accepts a promise that proves false, who is responsible for the incorrect result?
2. **How fine should the vocabulary be?** A coarse vocabulary repeats the problem of `deterministic`, and a fine one imposes declarations that authors may not supply. The right granularity is whatever the tests of actual features need to distinguish.
3. **What does an error mean in a predicate?** The predicate boundary settles nulls and leaves errors open. Is an error in a `check` constraint a violation, and does one in a filter fail the query or exclude the row?
4. **How long should engines keep old environments?** Under refusal, an index whose expression records a Unicode version that no current engine bundles costs speed instead of correctness, and provides no benefit until it is rebuilt.
5. **Where is a context recorded?** Recording it with each expression is exact and verbose. A table-level default that expressions may override is compact, and leaves open what happens to stored expressions when the default changes.
