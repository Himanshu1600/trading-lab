# Contracts

JSON Schemas shared by `core-service` and `ai-service`. Both sides validate against these files.

- `strategy-dsl.v1.schema.json` — the strategy DSL (PLAN.md §7.4). Its `examples` are parsed by `core-service`'s `StrategyParserTests` on every build, so the schema and the Java parser can't drift apart. `StrategyParser` is the final authority; the schema's `$comment` lists the few rules JSON Schema can't express.
- `events/` — event payloads such as `order-filled.v1.schema.json` (added in week 6)
