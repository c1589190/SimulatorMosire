# ScratchJacksonProbe 原始日志（探针已删，此为留档）

运行方式：./mvnw -q -pl simos-core -Dtest=ScratchJacksonProbe -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip=true -Dspotless.check.skip=true -Dspotbugs.skip=true -Denforcer.skip=true -Dprobe.log=… -Dprobe.out=… -Dprobe.run=N test（3 次 = 3 个独立 JVM）

## run0（独立 JVM 1）
```
PROBE|bare|STAGE|-|-
PROBE|bare|SER_FAIL|map|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.map.MapSnapshot["t...
PROBE|bare|SER_FAIL|social|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.social.SocialSnaps...
PROBE|bare|SER_FAIL|unit|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.unit.UnitSnapshot[...
PROBE|bare|SER_OK|GameMap-only|len=2597 sha256=13ea6b6306cee4f871d147838ad050b8c8cd22286fe440c5f651bbc19b7dcbc0 dump=/tmp/jackson-probe-run0/bare-GameMap-only-run0.json
PROBE|bare|DESER_FAIL|GameMap-only|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot find a (Map) Key deserializer for type [simple type, class io.mosire.simos.map.pathway.EdgeRef]  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 1]
PROBE|bare|SER_FAIL|MovementState-IN_TRANSIT|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<io.mosire.simos.map.hex.HexCoord>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.un...
PROBE|bare|SER_FAIL|MovementState-ARRIVED|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<io.mosire.simos.map.hex.HexCoord>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.un...
PROBE|bare|SER_OK|FieldDelta-Upsert|len=33 sha256=f7155eeb153967e64672c81326376c45092041ae2c631fef1bc5f63f297c7565 dump=/tmp/jackson-probe-run0/bare-FieldDelta-Upsert-run0.json
PROBE|bare|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|bare|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run0/bare-FieldDelta-Unchanged-run0.json
PROBE|bare|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|bare|SER_FAIL|SegmentedSeries-lambda-addition|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.util.time.Segmente...
PROBE|keys|STAGE|-|-
PROBE|keys|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|keys|SER_FAIL|map|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.map.MapSnapshot["t...
PROBE|keys|SER_FAIL|social|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.social.SocialSnaps...
PROBE|keys|SER_FAIL|unit|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.unit.UnitSnapshot[...
PROBE|keys|SER_OK|GameMap-only|len=2597 sha256=13ea6b6306cee4f871d147838ad050b8c8cd22286fe440c5f651bbc19b7dcbc0 dump=/tmp/jackson-probe-run0/keys-GameMap-only-run0.json
PROBE|keys|DESER_OK|GameMap-only|equals=true
PROBE|keys|SER_OK|FieldDelta-Upsert|len=33 sha256=f7155eeb153967e64672c81326376c45092041ae2c631fef1bc5f63f297c7565 dump=/tmp/jackson-probe-run0/keys-FieldDelta-Upsert-run0.json
PROBE|keys|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|keys|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run0/keys-FieldDelta-Unchanged-run0.json
PROBE|keys|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|keys|SER_FAIL|SegmentedSeries-lambda-addition|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.util.time.Segmente...
PROBE|keys+order|STAGE|-|-
PROBE|keys+order|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|keys+order|DETERM_FAIL|GameMap-only|sameInstance distinct=0; com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot order Map entries by key of incomparable type `io.mosire.simos.map.region.RegionId`, consider disabling `SerializationFeature.FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY` to simply skip sorting (through reference chain: io.mosire.simos.map.GameMap["regions"])
PROBE|keys+order|SER_FAIL|map|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.map.MapSnapshot["t...
PROBE|bare+order|STAGE|-|-
PROBE|bare+order|DETERM_FAIL|GameMap-only|sameInstance distinct=0; com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot order Map entries by key of incomparable type `io.mosire.simos.map.region.RegionId`, consider disabling `SerializationFeature.FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY` to simply skip sorting (through reference chain: io.mosire.simos.map.GameMap["regions"])
PROBE|jdk8+keys|STAGE|-|-
PROBE|jdk8+keys|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|jdk8+keys|NOTE|Jdk8Module|registered
PROBE|jdk8+keys|SER_OK|map|len=2709 sha256=713d68eedcd29a1767f3160f71b11dfdefc59a2925eafedb968d3488289d34e8 dump=/tmp/jackson-probe-run0/jdk8+keys-map-run0.json
PROBE|jdk8+keys|DESER_OK|map|equals=true
PROBE|jdk8+keys|SER_OK|social|len=384 sha256=d5ee82b831391333cfceb7d1a8617f83337151b2cfddfd922df303a1c928a787 dump=/tmp/jackson-probe-run0/jdk8+keys-social-run0.json
PROBE|jdk8+keys|DESER_OK|social|equals=true
PROBE|jdk8+keys|SER_OK|unit|len=1009 sha256=16dedaef95c4c1966560711c98d906059b52660fa5e5938578ac8c9ee5d2e06f dump=/tmp/jackson-probe-run0/jdk8+keys-unit-run0.json
PROBE|jdk8+keys|DESER_OK|unit|equals=true
PROBE|jdk8+keys|SER_OK|MovementState-IN_TRANSIT|len=104 sha256=321e105880bc94d19c005a77a638196504525807aa2df49c76cfb57e303ce2a5 dump=/tmp/jackson-probe-run0/jdk8+keys-MovementState-IN_TRANSIT-run0.json
PROBE|jdk8+keys|DESER_OK|MovementState-IN_TRANSIT|equals=true
PROBE|jdk8+keys|SER_OK|MovementState-ARRIVED|len=93 sha256=7b75938874583ad09859c5a832e1e36eaf6a34db34e19c092a787143aff0350d dump=/tmp/jackson-probe-run0/jdk8+keys-MovementState-ARRIVED-run0.json
PROBE|jdk8+keys|DESER_OK|MovementState-ARRIVED|equals=true
PROBE|jdk8+keys|SER_OK|FieldDelta-Upsert|len=33 sha256=f7155eeb153967e64672c81326376c45092041ae2c631fef1bc5f63f297c7565 dump=/tmp/jackson-probe-run0/jdk8+keys-FieldDelta-Upsert-run0.json
PROBE|jdk8+keys|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|jdk8+keys|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run0/jdk8+keys-FieldDelta-Unchanged-run0.json
PROBE|jdk8+keys|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|jdk8+keys|SER_OK|SegmentedSeries-lambda-addition|len=155 sha256=cd5b6f8d954a1446ce5f6103afb3cd4c26d9648f570663701d4315184f1843ef dump=/tmp/jackson-probe-run0/jdk8+keys-SegmentedSeries-lambda-addition-run0.json
PROBE|jdk8+keys|DESER_FAIL|SegmentedSeries-lambda-addition|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `java.util.function.BinaryOperator` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_...
PROBE|jdk8+keys|DETERM|map|sameInstance30 distinct=1; equalInstance30 distinct=1; sameVsRebuiltEqual=true sha256=713d68eedcd29a1767f3160f71b11dfdefc59a2925eafedb968d3488289d34e8
PROBE|typing|STAGE|-|-
PROBE|typing|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|typing|NOTE|Jdk8Module|registered
PROBE|typing|SER_OK|FieldDelta-Upsert|len=82 sha256=c4beca08b9b48ba997c3ad5c587eb729b748b35c8dc5fd1a9ace2a109339aa60 dump=/tmp/jackson-probe-run0/typing-FieldDelta-Upsert-run0.json
PROBE|typing|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidTypeIdException: Could not resolve subtype of [simple type, class io.mosire.simos.util.state.FieldDelta]: missing type id property '@class'  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 82]
PROBE|typing|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run0/typing-FieldDelta-Unchanged-run0.json
PROBE|typing|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidTypeIdException: Could not resolve subtype of [simple type, class io.mosire.simos.util.state.FieldDelta]: missing type id property '@class'  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 2]
PROBE|typing|SER_OK|map|len=3503 sha256=02106cbcf84786c479792e7302637a66745a92b17c7aae3bb1878a0df9d3e77c dump=/tmp/jackson-probe-run0/typing-map-run0.json
PROBE|typing|DESER_OK|map|equals=true
```

## run1（独立 JVM 2）
```
PROBE|bare|STAGE|-|-
PROBE|bare|SER_FAIL|map|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.map.MapSnapshot["t...
PROBE|bare|SER_FAIL|social|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.social.SocialSnaps...
PROBE|bare|SER_FAIL|unit|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.unit.UnitSnapshot[...
PROBE|bare|SER_OK|GameMap-only|len=2597 sha256=13ea6b6306cee4f871d147838ad050b8c8cd22286fe440c5f651bbc19b7dcbc0 dump=/tmp/jackson-probe-run1/bare-GameMap-only-run1.json
PROBE|bare|DESER_FAIL|GameMap-only|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot find a (Map) Key deserializer for type [simple type, class io.mosire.simos.map.pathway.EdgeRef]  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 1]
PROBE|bare|SER_FAIL|MovementState-IN_TRANSIT|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<io.mosire.simos.map.hex.HexCoord>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.un...
PROBE|bare|SER_FAIL|MovementState-ARRIVED|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<io.mosire.simos.map.hex.HexCoord>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.un...
PROBE|bare|SER_OK|FieldDelta-Upsert|len=33 sha256=0975c12dca0a346e3fd0bf3f95e85e4dd76f6de966070a5281cc87ab79ec2142 dump=/tmp/jackson-probe-run1/bare-FieldDelta-Upsert-run1.json
PROBE|bare|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|bare|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run1/bare-FieldDelta-Unchanged-run1.json
PROBE|bare|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|bare|SER_FAIL|SegmentedSeries-lambda-addition|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.util.time.Segmente...
PROBE|keys|STAGE|-|-
PROBE|keys|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|keys|SER_FAIL|map|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.map.MapSnapshot["t...
PROBE|keys|SER_FAIL|social|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.social.SocialSnaps...
PROBE|keys|SER_FAIL|unit|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.unit.UnitSnapshot[...
PROBE|keys|SER_OK|GameMap-only|len=2597 sha256=13ea6b6306cee4f871d147838ad050b8c8cd22286fe440c5f651bbc19b7dcbc0 dump=/tmp/jackson-probe-run1/keys-GameMap-only-run1.json
PROBE|keys|DESER_OK|GameMap-only|equals=true
PROBE|keys|SER_OK|FieldDelta-Upsert|len=33 sha256=0975c12dca0a346e3fd0bf3f95e85e4dd76f6de966070a5281cc87ab79ec2142 dump=/tmp/jackson-probe-run1/keys-FieldDelta-Upsert-run1.json
PROBE|keys|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|keys|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run1/keys-FieldDelta-Unchanged-run1.json
PROBE|keys|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|keys|SER_FAIL|SegmentedSeries-lambda-addition|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.util.time.Segmente...
PROBE|keys+order|STAGE|-|-
PROBE|keys+order|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|keys+order|DETERM_FAIL|GameMap-only|sameInstance distinct=0; com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot order Map entries by key of incomparable type `io.mosire.simos.map.region.RegionId`, consider disabling `SerializationFeature.FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY` to simply skip sorting (through reference chain: io.mosire.simos.map.GameMap["regions"])
PROBE|keys+order|SER_FAIL|map|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.map.MapSnapshot["t...
PROBE|bare+order|STAGE|-|-
PROBE|bare+order|DETERM_FAIL|GameMap-only|sameInstance distinct=0; com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot order Map entries by key of incomparable type `io.mosire.simos.map.region.RegionId`, consider disabling `SerializationFeature.FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY` to simply skip sorting (through reference chain: io.mosire.simos.map.GameMap["regions"])
PROBE|jdk8+keys|STAGE|-|-
PROBE|jdk8+keys|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|jdk8+keys|NOTE|Jdk8Module|registered
PROBE|jdk8+keys|SER_OK|map|len=2709 sha256=713d68eedcd29a1767f3160f71b11dfdefc59a2925eafedb968d3488289d34e8 dump=/tmp/jackson-probe-run1/jdk8+keys-map-run1.json
PROBE|jdk8+keys|DESER_OK|map|equals=true
PROBE|jdk8+keys|SER_OK|social|len=384 sha256=d5ee82b831391333cfceb7d1a8617f83337151b2cfddfd922df303a1c928a787 dump=/tmp/jackson-probe-run1/jdk8+keys-social-run1.json
PROBE|jdk8+keys|DESER_OK|social|equals=true
PROBE|jdk8+keys|SER_OK|unit|len=1009 sha256=16dedaef95c4c1966560711c98d906059b52660fa5e5938578ac8c9ee5d2e06f dump=/tmp/jackson-probe-run1/jdk8+keys-unit-run1.json
PROBE|jdk8+keys|DESER_OK|unit|equals=true
PROBE|jdk8+keys|SER_OK|MovementState-IN_TRANSIT|len=104 sha256=321e105880bc94d19c005a77a638196504525807aa2df49c76cfb57e303ce2a5 dump=/tmp/jackson-probe-run1/jdk8+keys-MovementState-IN_TRANSIT-run1.json
PROBE|jdk8+keys|DESER_OK|MovementState-IN_TRANSIT|equals=true
PROBE|jdk8+keys|SER_OK|MovementState-ARRIVED|len=93 sha256=7b75938874583ad09859c5a832e1e36eaf6a34db34e19c092a787143aff0350d dump=/tmp/jackson-probe-run1/jdk8+keys-MovementState-ARRIVED-run1.json
PROBE|jdk8+keys|DESER_OK|MovementState-ARRIVED|equals=true
PROBE|jdk8+keys|SER_OK|FieldDelta-Upsert|len=33 sha256=0975c12dca0a346e3fd0bf3f95e85e4dd76f6de966070a5281cc87ab79ec2142 dump=/tmp/jackson-probe-run1/jdk8+keys-FieldDelta-Upsert-run1.json
PROBE|jdk8+keys|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|jdk8+keys|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run1/jdk8+keys-FieldDelta-Unchanged-run1.json
PROBE|jdk8+keys|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|jdk8+keys|SER_OK|SegmentedSeries-lambda-addition|len=155 sha256=cd5b6f8d954a1446ce5f6103afb3cd4c26d9648f570663701d4315184f1843ef dump=/tmp/jackson-probe-run1/jdk8+keys-SegmentedSeries-lambda-addition-run1.json
PROBE|jdk8+keys|DESER_FAIL|SegmentedSeries-lambda-addition|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `java.util.function.BinaryOperator` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_...
PROBE|jdk8+keys|DETERM|map|sameInstance30 distinct=1; equalInstance30 distinct=1; sameVsRebuiltEqual=true sha256=713d68eedcd29a1767f3160f71b11dfdefc59a2925eafedb968d3488289d34e8
PROBE|typing|STAGE|-|-
PROBE|typing|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|typing|NOTE|Jdk8Module|registered
PROBE|typing|SER_OK|FieldDelta-Upsert|len=82 sha256=ededdaaf40997cd9da18de7e61bbdafbd7c379991e6e90640fca48d4ea322465 dump=/tmp/jackson-probe-run1/typing-FieldDelta-Upsert-run1.json
PROBE|typing|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidTypeIdException: Could not resolve subtype of [simple type, class io.mosire.simos.util.state.FieldDelta]: missing type id property '@class'  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 82]
PROBE|typing|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run1/typing-FieldDelta-Unchanged-run1.json
PROBE|typing|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidTypeIdException: Could not resolve subtype of [simple type, class io.mosire.simos.util.state.FieldDelta]: missing type id property '@class'  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 2]
PROBE|typing|SER_OK|map|len=3503 sha256=02106cbcf84786c479792e7302637a66745a92b17c7aae3bb1878a0df9d3e77c dump=/tmp/jackson-probe-run1/typing-map-run1.json
PROBE|typing|DESER_OK|map|equals=true
```

## run2（独立 JVM 3）
```
PROBE|bare|STAGE|-|-
PROBE|bare|SER_FAIL|map|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.map.MapSnapshot["t...
PROBE|bare|SER_FAIL|social|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.social.SocialSnaps...
PROBE|bare|SER_FAIL|unit|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.unit.UnitSnapshot[...
PROBE|bare|SER_OK|GameMap-only|len=2597 sha256=ec1892ef25416a21ef1f79e871360eb3de62517d98cd5a191571035d3214576a dump=/tmp/jackson-probe-run2/bare-GameMap-only-run2.json
PROBE|bare|DESER_FAIL|GameMap-only|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot find a (Map) Key deserializer for type [simple type, class io.mosire.simos.map.pathway.EdgeRef]  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 1]
PROBE|bare|SER_FAIL|MovementState-IN_TRANSIT|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<io.mosire.simos.map.hex.HexCoord>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.un...
PROBE|bare|SER_FAIL|MovementState-ARRIVED|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<io.mosire.simos.map.hex.HexCoord>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.un...
PROBE|bare|SER_OK|FieldDelta-Upsert|len=33 sha256=f7155eeb153967e64672c81326376c45092041ae2c631fef1bc5f63f297c7565 dump=/tmp/jackson-probe-run2/bare-FieldDelta-Upsert-run2.json
PROBE|bare|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|bare|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run2/bare-FieldDelta-Unchanged-run2.json
PROBE|bare|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|bare|SER_FAIL|SegmentedSeries-lambda-addition|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.util.time.Segmente...
PROBE|keys|STAGE|-|-
PROBE|keys|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|keys|SER_FAIL|map|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.map.MapSnapshot["t...
PROBE|keys|SER_FAIL|social|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.social.SocialSnaps...
PROBE|keys|SER_FAIL|unit|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.unit.UnitSnapshot[...
PROBE|keys|SER_OK|GameMap-only|len=2597 sha256=ec1892ef25416a21ef1f79e871360eb3de62517d98cd5a191571035d3214576a dump=/tmp/jackson-probe-run2/keys-GameMap-only-run2.json
PROBE|keys|DESER_OK|GameMap-only|equals=true
PROBE|keys|SER_OK|FieldDelta-Upsert|len=33 sha256=f7155eeb153967e64672c81326376c45092041ae2c631fef1bc5f63f297c7565 dump=/tmp/jackson-probe-run2/keys-FieldDelta-Upsert-run2.json
PROBE|keys|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|keys|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run2/keys-FieldDelta-Unchanged-run2.json
PROBE|keys|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|keys|SER_FAIL|SegmentedSeries-lambda-addition|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.util.time.Segmente...
PROBE|keys+order|STAGE|-|-
PROBE|keys+order|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|keys+order|DETERM_FAIL|GameMap-only|sameInstance distinct=0; com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot order Map entries by key of incomparable type `io.mosire.simos.map.region.RegionId`, consider disabling `SerializationFeature.FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY` to simply skip sorting (through reference chain: io.mosire.simos.map.GameMap["regions"])
PROBE|keys+order|SER_FAIL|map|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 optional type `java.util.Optional<java.lang.String>` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8" to enable handling (or disable `MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS`) (through reference chain: io.mosire.simos.map.MapSnapshot["t...
PROBE|bare+order|STAGE|-|-
PROBE|bare+order|DETERM_FAIL|GameMap-only|sameInstance distinct=0; com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot order Map entries by key of incomparable type `io.mosire.simos.map.region.RegionId`, consider disabling `SerializationFeature.FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY` to simply skip sorting (through reference chain: io.mosire.simos.map.GameMap["regions"])
PROBE|jdk8+keys|STAGE|-|-
PROBE|jdk8+keys|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|jdk8+keys|NOTE|Jdk8Module|registered
PROBE|jdk8+keys|SER_OK|map|len=2709 sha256=a1fab857d32f925a313ddc1e9737158963519c16a6fdb183ba145148454d11ae dump=/tmp/jackson-probe-run2/jdk8+keys-map-run2.json
PROBE|jdk8+keys|DESER_OK|map|equals=true
PROBE|jdk8+keys|SER_OK|social|len=384 sha256=d5ee82b831391333cfceb7d1a8617f83337151b2cfddfd922df303a1c928a787 dump=/tmp/jackson-probe-run2/jdk8+keys-social-run2.json
PROBE|jdk8+keys|DESER_OK|social|equals=true
PROBE|jdk8+keys|SER_OK|unit|len=1009 sha256=16dedaef95c4c1966560711c98d906059b52660fa5e5938578ac8c9ee5d2e06f dump=/tmp/jackson-probe-run2/jdk8+keys-unit-run2.json
PROBE|jdk8+keys|DESER_OK|unit|equals=true
PROBE|jdk8+keys|SER_OK|MovementState-IN_TRANSIT|len=104 sha256=321e105880bc94d19c005a77a638196504525807aa2df49c76cfb57e303ce2a5 dump=/tmp/jackson-probe-run2/jdk8+keys-MovementState-IN_TRANSIT-run2.json
PROBE|jdk8+keys|DESER_OK|MovementState-IN_TRANSIT|equals=true
PROBE|jdk8+keys|SER_OK|MovementState-ARRIVED|len=93 sha256=7b75938874583ad09859c5a832e1e36eaf6a34db34e19c092a787143aff0350d dump=/tmp/jackson-probe-run2/jdk8+keys-MovementState-ARRIVED-run2.json
PROBE|jdk8+keys|DESER_OK|MovementState-ARRIVED|equals=true
PROBE|jdk8+keys|SER_OK|FieldDelta-Upsert|len=33 sha256=f7155eeb153967e64672c81326376c45092041ae2c631fef1bc5f63f297c7565 dump=/tmp/jackson-probe-run2/jdk8+keys-FieldDelta-Upsert-run2.json
PROBE|jdk8+keys|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|jdk8+keys|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run2/jdk8+keys-FieldDelta-Unchanged-run2.json
PROBE|jdk8+keys|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `io.mosire.simos.util.state.FieldDelta` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE...
PROBE|jdk8+keys|SER_OK|SegmentedSeries-lambda-addition|len=155 sha256=cd5b6f8d954a1446ce5f6103afb3cd4c26d9648f570663701d4315184f1843ef dump=/tmp/jackson-probe-run2/jdk8+keys-SegmentedSeries-lambda-addition-run2.json
PROBE|jdk8+keys|DESER_FAIL|SegmentedSeries-lambda-addition|com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `java.util.function.BinaryOperator` (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_...
PROBE|jdk8+keys|DETERM|map|sameInstance30 distinct=1; equalInstance30 distinct=1; sameVsRebuiltEqual=true sha256=a1fab857d32f925a313ddc1e9737158963519c16a6fdb183ba145148454d11ae
PROBE|typing|STAGE|-|-
PROBE|typing|NOTE|KeyDeserializers|registered: HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId（test 内注册，未改主源码）
PROBE|typing|NOTE|Jdk8Module|registered
PROBE|typing|SER_OK|FieldDelta-Upsert|len=82 sha256=c4beca08b9b48ba997c3ad5c587eb729b748b35c8dc5fd1a9ace2a109339aa60 dump=/tmp/jackson-probe-run2/typing-FieldDelta-Upsert-run2.json
PROBE|typing|DESER_FAIL|FieldDelta-Upsert|com.fasterxml.jackson.databind.exc.InvalidTypeIdException: Could not resolve subtype of [simple type, class io.mosire.simos.util.state.FieldDelta]: missing type id property '@class'  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 82]
PROBE|typing|SER_OK|FieldDelta-Unchanged|len=2 sha256=44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a dump=/tmp/jackson-probe-run2/typing-FieldDelta-Unchanged-run2.json
PROBE|typing|DESER_FAIL|FieldDelta-Unchanged|com.fasterxml.jackson.databind.exc.InvalidTypeIdException: Could not resolve subtype of [simple type, class io.mosire.simos.util.state.FieldDelta]: missing type id property '@class'  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 2]
PROBE|typing|SER_OK|map|len=3503 sha256=e42205b907af42622dec0120a7b6b764fa9982dd12c4792d0868b15f11ed32f3 dump=/tmp/jackson-probe-run2/typing-map-run2.json
PROBE|typing|DESER_OK|map|equals=true
```

## 3-JVM dump 对照
```
/tmp/jackson-probe-run0:
drwxrwxr-x  2 dev  dev  4096 Sep 18 05:20 .
drwxrwxrwt 26 root root 4096 Sep 18 05:26 ..
-rw-rw-r--  1 dev  dev     2 Sep 18 05:20 bare-FieldDelta-Unchanged-run0.json
-rw-rw-r--  1 dev  dev    33 Sep 18 05:20 bare-FieldDelta-Upsert-run0.json
-rw-rw-r--  1 dev  dev  2615 Sep 18 05:20 bare-GameMap-only-run0.json
-rw-rw-r--  1 dev  dev     2 Sep 18 05:20 jdk8+keys-FieldDelta-Unchanged-run0.json
-rw-rw-r--  1 dev  dev    33 Sep 18 05:20 jdk8+keys-FieldDelta-Upsert-run0.json
-rw-rw-r--  1 dev  dev    93 Sep 18 05:20 jdk8+keys-MovementState-ARRIVED-run0.json
-rw-rw-r--  1 dev  dev   104 Sep 18 05:20 jdk8+keys-MovementState-IN_TRANSIT-run0.json
-rw-rw-r--  1 dev  dev   155 Sep 18 05:20 jdk8+keys-SegmentedSeries-lambda-addition-run0.json
-rw-rw-r--  1 dev  dev  2727 Sep 18 05:20 jdk8+keys-map-determ-run0.json
-rw-rw-r--  1 dev  dev  2727 Sep 18 05:20 jdk8+keys-map-run0.json
-rw-rw-r--  1 dev  dev   384 Sep 18 05:20 jdk8+keys-social-run0.json
-rw-rw-r--  1 dev  dev  1017 Sep 18 05:20 jdk8+keys-unit-run0.json
-rw-rw-r--  1 dev  dev     2 Sep 18 05:20 keys-FieldDelta-Unchanged-run0.json
-rw-rw-r--  1 dev  dev    33 Sep 18 05:20 keys-FieldDelta-Upsert-run0.json
-rw-rw-r--  1 dev  dev  2615 Sep 18 05:20 keys-GameMap-only-run0.json
-rw-rw-r--  1 dev  dev     2 Sep 18 05:20 typing-FieldDelta-Unchanged-run0.json
-rw-rw-r--  1 dev  dev    82 Sep 18 05:20 typing-FieldDelta-Upsert-run0.json
-rw-rw-r--  1 dev  dev  3521 Sep 18 05:20 typing-map-run0.json

/tmp/jackson-probe-run1:
drwxrwxr-x  2 dev  dev  4096 Sep 18 05:23 .
drwxrwxrwt 26 root root 4096 Sep 18 05:26 ..
-rw-rw-r--  1 dev  dev     2 Sep 18 05:23 bare-FieldDelta-Unchanged-run1.json
-rw-rw-r--  1 dev  dev    33 Sep 18 05:23 bare-FieldDelta-Upsert-run1.json
-rw-rw-r--  1 dev  dev  2615 Sep 18 05:23 bare-GameMap-only-run1.json
-rw-rw-r--  1 dev  dev     2 Sep 18 05:23 jdk8+keys-FieldDelta-Unchanged-run1.json
-rw-rw-r--  1 dev  dev    33 Sep 18 05:23 jdk8+keys-FieldDelta-Upsert-run1.json
-rw-rw-r--  1 dev  dev    93 Sep 18 05:23 jdk8+keys-MovementState-ARRIVED-run1.json
-rw-rw-r--  1 dev  dev   104 Sep 18 05:23 jdk8+keys-MovementState-IN_TRANSIT-run1.json
-rw-rw-r--  1 dev  dev   155 Sep 18 05:23 jdk8+keys-SegmentedSeries-lambda-addition-run1.json
-rw-rw-r--  1 dev  dev  2727 Sep 18 05:23 jdk8+keys-map-determ-run1.json
-rw-rw-r--  1 dev  dev  2727 Sep 18 05:23 jdk8+keys-map-run1.json
-rw-rw-r--  1 dev  dev   384 Sep 18 05:23 jdk8+keys-social-run1.json
-rw-rw-r--  1 dev  dev  1017 Sep 18 05:23 jdk8+keys-unit-run1.json
-rw-rw-r--  1 dev  dev     2 Sep 18 05:23 keys-FieldDelta-Unchanged-run1.json
-rw-rw-r--  1 dev  dev    33 Sep 18 05:23 keys-FieldDelta-Upsert-run1.json
-rw-rw-r--  1 dev  dev  2615 Sep 18 05:23 keys-GameMap-only-run1.json
-rw-rw-r--  1 dev  dev     2 Sep 18 05:23 typing-FieldDelta-Unchanged-run1.json
-rw-rw-r--  1 dev  dev    82 Sep 18 05:23 typing-FieldDelta-Upsert-run1.json
-rw-rw-r--  1 dev  dev  3521 Sep 18 05:23 typing-map-run1.json

/tmp/jackson-probe-run2:
drwxrwxr-x  2 dev  dev  4096 Sep 18 05:23 .
drwxrwxrwt 26 root root 4096 Sep 18 05:26 ..
-rw-rw-r--  1 dev  dev     2 Sep 18 05:23 bare-FieldDelta-Unchanged-run2.json
-rw-rw-r--  1 dev  dev    33 Sep 18 05:23 bare-FieldDelta-Upsert-run2.json
-rw-rw-r--  1 dev  dev  2615 Sep 18 05:23 bare-GameMap-only-run2.json
-rw-rw-r--  1 dev  dev     2 Sep 18 05:23 jdk8+keys-FieldDelta-Unchanged-run2.json
-rw-rw-r--  1 dev  dev    33 Sep 18 05:23 jdk8+keys-FieldDelta-Upsert-run2.json
-rw-rw-r--  1 dev  dev    93 Sep 18 05:23 jdk8+keys-MovementState-ARRIVED-run2.json
-rw-rw-r--  1 dev  dev   104 Sep 18 05:23 jdk8+keys-MovementState-IN_TRANSIT-run2.json
-rw-rw-r--  1 dev  dev   155 Sep 18 05:23 jdk8+keys-SegmentedSeries-lambda-addition-run2.json
-rw-rw-r--  1 dev  dev  2727 Sep 18 05:23 jdk8+keys-map-determ-run2.json
-rw-rw-r--  1 dev  dev  2727 Sep 18 05:23 jdk8+keys-map-run2.json
-rw-rw-r--  1 dev  dev   384 Sep 18 05:23 jdk8+keys-social-run2.json
-rw-rw-r--  1 dev  dev  1017 Sep 18 05:23 jdk8+keys-unit-run2.json
-rw-rw-r--  1 dev  dev     2 Sep 18 05:23 keys-FieldDelta-Unchanged-run2.json
-rw-rw-r--  1 dev  dev    33 Sep 18 05:23 keys-FieldDelta-Upsert-run2.json
-rw-rw-r--  1 dev  dev  2615 Sep 18 05:23 keys-GameMap-only-run2.json
-rw-rw-r--  1 dev  dev     2 Sep 18 05:23 typing-FieldDelta-Unchanged-run2.json
-rw-rw-r--  1 dev  dev    82 Sep 18 05:23 typing-FieldDelta-Upsert-run2.json
-rw-rw-r--  1 dev  dev  3521 Sep 18 05:23 typing-map-run2.json
5ab846f23cd807e51c06278573b34afa  /tmp/jackson-probe-run0/jdk8+keys-map-run0.json
5ab846f23cd807e51c06278573b34afa  /tmp/jackson-probe-run1/jdk8+keys-map-run1.json
196190f59ed306c9ecadef5fc8cd07d4  /tmp/jackson-probe-run2/jdk8+keys-map-run2.json
dcda673cbbdf7f124359df987d35a456  /tmp/jackson-probe-run0/typing-map-run0.json
dcda673cbbdf7f124359df987d35a456  /tmp/jackson-probe-run1/typing-map-run1.json
c5fecf0940dab3e6e4d1429a682fe7c6  /tmp/jackson-probe-run2/typing-map-run2.json
```
