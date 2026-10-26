/*-
 * #%L
 * Serve MCP
 * %%
 * Copyright (C) 2026 Reed von Redwitz
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package build.serve.mcp.tool;

import build.base.json.Json;
import build.base.json.JsonObject;
import build.serve.mcp.McpToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ToolParamTest {

    // --- Base behavior (tested once via StringParam as the representative type) ---

    @Test
    void shouldThrowWhenRequiredParamMissing() {
        final var param = ToolParam.string("city", "City name");
        assertThatThrownBy(() -> param.extract(Json.parse("{}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("city");
    }

    @Test
    void shouldThrowWhenRequiredParamNull() {
        final var param = ToolParam.string("city", "City name");
        assertThatThrownBy(() -> param.extract(Json.parse("{\"city\":null}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("city");
    }

    @Test
    void shouldReturnNullWhenOptionalParamMissing() {
        assertThat(ToolParam.string("city", "City name").optional().extract(Json.parse("{}"))).isNull();
    }

    @Test
    void shouldReturnNullWhenOptionalParamNull() {
        assertThat(ToolParam.string("city", "City name").optional().extract(Json.parse("{\"city\":null}"))).isNull();
    }

    @Test
    void shouldReturnDefaultWhenOptionalParamMissing() {
        assertThat(ToolParam.string("city", "City name").optional("Paris").extract(Json.parse("{}"))).isEqualTo("Paris");
    }

    @Test
    void shouldReturnDefaultWhenOptionalParamNull() {
        assertThat(ToolParam.string("city", "City name").optional("Paris").extract(Json.parse("{\"city\":null}"))).isEqualTo("Paris");
    }

    @Test
    void shouldReturnValueWhenOptionalParamPresent() {
        assertThat(ToolParam.string("city", "City name").optional("Paris").extract(Json.parse("{\"city\":\"Berlin\"}"))).isEqualTo("Berlin");
    }

    @Test
    void shouldReportRequiredForNewParam() {
        assertThat(ToolParam.string("x", "x").isRequired()).isTrue();
    }

    @Test
    void shouldNotBeRequiredAfterOptional() {
        assertThat(ToolParam.string("x", "x").optional().isRequired()).isFalse();
    }

    // --- Factory return types ---

    @Test
    void shouldReturnStringParamFromStringFactory() {
        assertThat(ToolParam.string("x", "x")).isInstanceOf(StringParam.class);
    }

    @Test
    void shouldReturnNumberParamIntegerFromIntegerFactory() {
        final NumberParam<Integer> param = ToolParam.integer("x", "x");
        assertThat(param).isInstanceOf(NumberParam.class);
    }

    @Test
    void shouldReturnNumberParamDoubleFromNumberFactory() {
        final NumberParam<Double> param = ToolParam.number("x", "x");
        assertThat(param).isInstanceOf(NumberParam.class);
    }

    @Test
    void shouldReturnBoolParamFromBoolFactory() {
        assertThat(ToolParam.bool("x", "x")).isInstanceOf(BoolParam.class);
    }

    @Test
    void shouldReturnArrayParamFromUntypedArrayFactory() {
        assertThat(ToolParam.array("x", "x")).isInstanceOf(ArrayParam.class);
    }

    @Test
    void shouldReturnArrayParamFromTypedArrayFactory() {
        assertThat(ToolParam.array("x", "x", ToolParam.string("item", "item"))).isInstanceOf(ArrayParam.class);
    }

    @Test
    void shouldReturnObjectParamFromObjectFactory() {
        assertThat(ToolParam.object("x", "x", List.of())).isInstanceOf(ObjectParam.class);
    }

    @Test
    void shouldReturnStringParamFromValuesMethod() {
        assertThat(ToolParam.string("x", "x").values(List.of("a"))).isInstanceOf(StringParam.class);
    }

    @Test
    void shouldPreserveSpecificTypeAfterOptional() {
        assertThat(ToolParam.string("x", "x").optional()).isInstanceOf(StringParam.class);
        assertThat(ToolParam.integer("x", "x").optional()).isInstanceOf(NumberParam.class);
        assertThat(ToolParam.bool("x", "x").optional()).isInstanceOf(BoolParam.class);
    }

    // --- Per-type: extraction value and schema content ---

    @Test
    void shouldExtractStringValue() {
        assertThat(ToolParam.string("city", "City name").extract(Json.parse("{\"city\":\"London\"}"))).isEqualTo("London");
    }

    @Test
    void shouldProduceStringSchema() {
        final var schema = ToolParam.string("city", "City name").propertySchema();
        assertThat(schema.getString("type")).isEqualTo("string");
        assertThat(schema.getString("description")).isEqualTo("City name");
    }

    @Test
    void shouldExtractIntegerValue() {
        assertThat(ToolParam.integer("count", "Count").extract(Json.parse("{\"count\":42}"))).isEqualTo(42);
    }

    @Test
    void shouldProduceIntegerSchema() {
        final var schema = ToolParam.integer("count", "Count").propertySchema();
        assertThat(schema.getString("type")).isEqualTo("integer");
    }

    @Test
    void shouldExtractDoubleValue() {
        assertThat(ToolParam.number("tempo", "BPM").extract(Json.parse("{\"tempo\":120.5}"))).isEqualTo(120.5);
    }

    @Test
    void shouldProduceNumberSchema() {
        final var schema = ToolParam.number("tempo", "BPM").propertySchema();
        assertThat(schema.getString("type")).isEqualTo("number");
    }

    @Test
    void shouldExtractBoolTrue() {
        assertThat(ToolParam.bool("shuffle", "Shuffle").extract(Json.parse("{\"shuffle\":true}"))).isTrue();
    }

    @Test
    void shouldExtractBoolFalse() {
        assertThat(ToolParam.bool("shuffle", "Shuffle").extract(Json.parse("{\"shuffle\":false}"))).isFalse();
    }

    @Test
    void shouldProduceBooleanSchema() {
        final var schema = ToolParam.bool("shuffle", "Shuffle").propertySchema();
        assertThat(schema.getString("type")).isEqualTo("boolean");
    }

    @Test
    void shouldExtractUntypedArrayAsList() {
        final var result = ToolParam.array("steps", "Steps").extract(Json.parse("{\"steps\":[\"a\",\"b\"]}"));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).asString().value()).isEqualTo("a");
    }

    @Test
    void shouldProduceArraySchema() {
        final var schema = ToolParam.array("steps", "Steps").propertySchema();
        assertThat(schema.getString("type")).isEqualTo("array");
    }

    @Test
    void shouldExtractValueInValuesList() {
        assertThat(ToolParam.string("dir", "Direction").values(List.of("up", "down")).extract(Json.parse("{\"dir\":\"up\"}"))).isEqualTo("up");
    }

    @Test
    void shouldThrowWhenValueNotInValuesList() {
        final var param = ToolParam.string("dir", "Direction").values(List.of("up", "down"));
        assertThatThrownBy(() -> param.extract(Json.parse("{\"dir\":\"left\"}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dir");
    }

    @Test
    void shouldProduceEnumSchemaFromValues() {
        final var schema = ToolParam.string("dir", "Direction").values(List.of("up", "down")).propertySchema();
        assertThat(schema.has("enum")).isTrue();
        final var enumArr = schema.get("enum").asArray();
        assertThat(enumArr.element(0).asString().value()).isEqualTo("up");
        assertThat(enumArr.element(1).asString().value()).isEqualTo("down");
    }

    // --- Composable: object ---

    private static final StringParam STEP_TYPE = ToolParam.string("type", "Step type").values(List.of("seed", "filter", "terminal"));
    private static final ToolParam<String> STEP_ID = ToolParam.string("id", "Step ID");
    private static final ObjectParam STEP = ToolParam.object("step", "A pipeline step", List.of(STEP_TYPE, STEP_ID));

    @Test
    void shouldExtractNestedObjectFields() {
        final var step = STEP.extract(Json.parse("{\"step\":{\"type\":\"seed\",\"id\":\"notes\"}}"));
        assertThat(STEP_TYPE.extract(step)).isEqualTo("seed");
        assertThat(STEP_ID.extract(step)).isEqualTo("notes");
    }

    @Test
    void shouldProduceObjectSchemaWithPropertiesAndRequired() {
        final var schema = STEP.propertySchema();
        assertThat(schema.getString("type")).isEqualTo("object");
        assertThat(schema.get("properties").asObject().has("type")).isTrue();
        assertThat(schema.get("properties").asObject().has("id")).isTrue();
        assertThat(schema.get("required").asArray().values()).hasSize(2);
    }

    @Test
    void shouldThrowWhenObjectRequiredFieldMissing() {
        assertThatThrownBy(() -> STEP.extract(Json.parse("{\"step\":{\"type\":\"seed\"}}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id");
    }

    @Test
    void shouldThrowWhenObjectFieldValueNotInAllowedList() {
        assertThatThrownBy(() -> STEP.extract(Json.parse("{\"step\":{\"type\":\"bogus\",\"id\":\"x\"}}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type");
    }

    // --- Composable: typed array (pipeline) ---

    private static final ArrayParam<JsonObject> PIPELINE = ToolParam.array("pipeline", "Ordered steps", STEP);

    @Test
    void shouldExtractTypedArrayItems() {
        final var steps = PIPELINE.extract(Json.parse("""
            {"pipeline":[
                {"type":"seed","id":"notes"},
                {"type":"terminal","id":"collect"}
            ]}"""));
        assertThat(steps).hasSize(2);
        assertThat(STEP_TYPE.extract(steps.get(0))).isEqualTo("seed");
        assertThat(STEP_TYPE.extract(steps.get(1))).isEqualTo("terminal");
    }

    @Test
    void shouldProduceTypedArraySchemaWithItems() {
        final var schema = PIPELINE.propertySchema();
        assertThat(schema.getString("type")).isEqualTo("array");
        assertThat(schema.get("items").asObject().getString("type")).isEqualTo("object");
        assertThat(schema.get("items").asObject().get("properties").asObject().has("type")).isTrue();
    }

    @Test
    void shouldThrowWhenArrayItemMissingRequiredField() {
        assertThatThrownBy(() -> PIPELINE.extract(Json.parse("""
            {"pipeline":[
                {"type":"seed","id":"notes"},
                {"type":"filter"}
            ]}""")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id");
    }

    // --- of() escape hatch ---

    @Test
    void shouldExtractValueWithCustomSchemaAndExtractor() {
        final var schema = JsonObject.builder().put("type", "object").put("description", "Pipeline").build();
        final var param = ToolParam.of("pipeline", "Pipeline", schema, val -> val.asObject());
        final var result = param.extract(Json.parse("{\"pipeline\":{\"seed\":\"notes\"}}"));
        assertThat(result.getString("seed")).isEqualTo("notes");
    }

    @Test
    void shouldPassThroughCustomSchema() {
        final var schema = JsonObject.builder().put("type", "object").put("description", "Custom").build();
        assertThat(ToolParam.of("p", "Custom", schema, val -> val).propertySchema().getString("type")).isEqualTo("object");
    }

    // --- StringParam.minLength / maxLength ---

    @Test
    void shouldThrowWhenStringTooShort() {
        final var param = ToolParam.string("code", "Code").minLength(3);
        assertThatThrownBy(() -> param.extract(Json.parse("{\"code\":\"ab\"}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("code");
    }

    @Test
    void shouldPassWhenStringAtMinLength() {
        assertThat(ToolParam.string("code", "Code").minLength(3).extract(Json.parse("{\"code\":\"abc\"}"))).isEqualTo("abc");
    }

    @Test
    void shouldThrowWhenStringTooLong() {
        final var param = ToolParam.string("code", "Code").maxLength(3);
        assertThatThrownBy(() -> param.extract(Json.parse("{\"code\":\"abcd\"}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("code");
    }

    @Test
    void shouldProduceMinLengthInSchema() {
        final var schema = ToolParam.string("code", "Code").minLength(3).propertySchema();
        assertThat(schema.get("minLength").asNumber().toNumber().intValue()).isEqualTo(3);
    }

    @Test
    void shouldAccumulateSchemaWhenChainingStringConstraints() {
        final var schema = ToolParam.string("code", "Code").minLength(2).maxLength(10).propertySchema();
        assertThat(schema.get("minLength").asNumber().toNumber().intValue()).isEqualTo(2);
        assertThat(schema.get("maxLength").asNumber().toNumber().intValue()).isEqualTo(10);
    }

    // --- refine ---

    @Test
    void shouldReturnStringParamFromRefine() {
        assertThat(ToolParam.string("code", "Code").refine(s -> true, "")).isInstanceOf(StringParam.class);
    }

    @Test
    void shouldExtractWhenRefinePredicatePasses() {
        final var param = ToolParam.string("code", "Code").refine(s -> s.startsWith("A"), "must start with A");
        assertThat(param.extract(Json.parse("{\"code\":\"Alpha\"}"))).isEqualTo("Alpha");
    }

    @Test
    void shouldThrowWhenRefinePredicateFails() {
        final var param = ToolParam.string("code", "Code").refine(s -> s.startsWith("A"), "must start with A");
        assertThatThrownBy(() -> param.extract(Json.parse("{\"code\":\"Beta\"}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must start with A");
    }

    // --- safeParse ---

    @Test
    void shouldReturnEmptyFromSafeParseWhenMissing() {
        assertThat(ToolParam.string("x", "x").safeParse(Json.parse("{}"))).isEmpty();
    }

    @Test
    void shouldReturnValueFromSafeParseWhenPresent() {
        assertThat(ToolParam.string("x", "x").safeParse(Json.parse("{\"x\":\"hello\"}"))).contains("hello");
    }

    // --- NumberParam.min / max ---

    @Test
    void shouldThrowWhenNumberBelowMin() {
        final var param = ToolParam.integer("count", "Count").min(1);
        assertThatThrownBy(() -> param.extract(Json.parse("{\"count\":0}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("count");
    }

    @Test
    void shouldPassWhenNumberAtMin() {
        assertThat(ToolParam.integer("count", "Count").min(1).extract(Json.parse("{\"count\":1}"))).isEqualTo(1);
    }

    @Test
    void shouldThrowWhenNumberAboveMax() {
        final var param = ToolParam.integer("count", "Count").max(10);
        assertThatThrownBy(() -> param.extract(Json.parse("{\"count\":11}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("count");
    }

    @Test
    void shouldProduceMinimumInSchema() {
        final var schema = ToolParam.number("tempo", "BPM").min(60.0).propertySchema();
        assertThat(schema.get("minimum").asNumber().toNumber().doubleValue()).isEqualTo(60.0);
    }

    @Test
    void shouldAccumulateSchemaWhenChainingNumberConstraints() {
        final var schema = ToolParam.number("tempo", "BPM").min(20.0).max(300.0).propertySchema();
        assertThat(schema.get("minimum").asNumber().toNumber().doubleValue()).isEqualTo(20.0);
        assertThat(schema.get("maximum").asNumber().toNumber().doubleValue()).isEqualTo(300.0);
    }

    // --- safeParse with wrong JSON type ---

    @Test
    void shouldReturnEmptyFromSafeParseOnWrongJsonType() {
        final var param = ToolParam.string("city", "City name");
        assertThat(param.safeParse(Json.parse("{\"city\":42}"))).isEmpty();
    }

    // --- ArrayParam.minItems / maxItems ---

    @Test
    void shouldThrowWhenArrayTooShort() {
        final var param = ToolParam.array("tags", "Tags").minItems(2);
        assertThatThrownBy(() -> param.extract(Json.parse("{\"tags\":[\"a\"]}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tags");
    }

    @Test
    void shouldThrowWhenArrayTooLong() {
        final var param = ToolParam.array("tags", "Tags").maxItems(2);
        assertThatThrownBy(() -> param.extract(Json.parse("{\"tags\":[\"a\",\"b\",\"c\"]}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tags");
    }

    @Test
    void shouldProduceMinItemsInSchema() {
        final var schema = ToolParam.array("tags", "Tags").minItems(1).propertySchema();
        assertThat(schema.get("minItems").asNumber().toNumber().intValue()).isEqualTo(1);
    }

    // --- ArrayParam.optional() defaults to List.of() ---

    @Test
    void shouldReturnEmptyListWhenOptionalArrayMissing() {
        final var param = ToolParam.array("tags", "Tags").optional();
        assertThat(param.extract(Json.parse("{}"))).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenOptionalArrayNull() {
        final var param = ToolParam.array("tags", "Tags").optional();
        assertThat(param.extract(Json.parse("{\"tags\":null}"))).isEmpty();
    }

    // --- map ---

    @Test
    void shouldTransformExtractedValueWithMap() {
        final var param = ToolParam.integer("count", "Count").map(n -> n * 2);
        assertThat(param.extract(Json.parse("{\"count\":5}"))).isEqualTo(10);
    }

    @Test
    void shouldPreserveSchemaAfterMap() {
        final var param = ToolParam.integer("count", "Count").map(n -> n * 2);
        assertThat(param.propertySchema().getString("type")).isEqualTo("integer");
    }

    @Test
    void shouldMapToStringType() {
        final var param = ToolParam.integer("n", "Number").map(Object::toString);
        assertThat(param.extract(Json.parse("{\"n\":42}"))).isEqualTo("42");
    }

    /**
     * {@code map()} wraps its result in a {@code GenericParam}, which has no {@code defs()} override, so
     * any {@code lazy()}-backed recursive field nested inside the mapped param's schema is dropped —
     * leaving a dangling {@code $ref} with no matching {@code $defs} entry. This is exactly the pattern
     * the discriminated {@code oneOf}'s own javadoc recommends (mapping a variant's raw extraction result
     * to a shared type), so a recursive variant silently produces an invalid schema.
     */
    @Test
    void shouldPreserveNestedDefsAfterMap() {
        final ToolParam<JsonObject>[] holder = new ToolParam[1];
        final var childRef = ToolParam.lazy("child", "Child node", "Node", () -> holder[0]);
        final var node = ToolParam.object("node", "A tree node", List.of(
            ToolParam.string("value", "Node value"),
            childRef.optional()));
        holder[0] = node;

        final var mapped = node.map(obj -> obj);
        assertThat(mapped.defs()).containsKey("Node");
    }

    // --- enumParam ---

    @Test
    void shouldExtractTypedValueFromEnumParam() {
        final var param = ToolParam.enumParam("dir", "Direction", List.of(
            Map.entry("up", 1),
            Map.entry("down", -1)
        ));
        assertThat(param.extract(Json.parse("{\"dir\":\"down\"}"))).isEqualTo(-1);
    }

    @Test
    void shouldThrowForUnknownEnumValue() {
        final var param = ToolParam.enumParam("dir", "Direction", List.of(
            Map.entry("up", 1),
            Map.entry("down", -1)
        ));
        assertThatThrownBy(() -> param.extract(Json.parse("{\"dir\":\"left\"}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dir");
    }

    @Test
    void shouldProduceEnumSchemaFromEnumParam() {
        final var schema = ToolParam.enumParam("dir", "Direction", List.of(
            Map.entry("up", 1),
            Map.entry("down", -1)
        )).propertySchema();
        assertThat(schema.getString("type")).isEqualTo("string");
        assertThat(schema.get("enum").asArray().values()).hasSize(2);
        assertThat(schema.get("enum").asArray().element(0).asString().value()).isEqualTo("up");
    }

    // --- oneOf (untyped) ---

    @Test
    void shouldExtractRawObjectFromUntypedOneOf() {
        final var circleParam = ToolParam.object("shape", "Circle", List.of(ToolParam.number("r", "Radius")));
        final var rectParam = ToolParam.object("shape", "Rect", List.of(ToolParam.number("w", "Width")));
        final var param = ToolParam.oneOf("shape", "A shape", List.of(circleParam, rectParam));
        final var result = param.extract(Json.parse("{\"shape\":{\"r\":5}}"));
        assertThat(result.get("r").asNumber().toNumber().doubleValue()).isEqualTo(5.0);
    }

    @Test
    void shouldProduceOneOfSchemaFromUntypedOneOf() {
        final var p1 = ToolParam.object("s", "A", List.of());
        final var p2 = ToolParam.object("s", "B", List.of());
        final var schema = ToolParam.oneOf("s", "One of", List.of(p1, p2)).propertySchema();
        assertThat(schema.has("oneOf")).isTrue();
        assertThat(schema.get("oneOf").asArray().values()).hasSize(2);
    }

    /**
     * {@code oneOf} wraps its result in a {@code GenericParam} built purely from each variant's
     * {@code propertySchema()}, never calling {@code variant.defs()} — so a variant containing a
     * {@code lazy()}-backed recursive field has its {@code $defs} entry silently dropped.
     */
    @Test
    void shouldIncludeVariantDefsInUntypedOneOfSchema() {
        final ToolParam<JsonObject>[] holder = new ToolParam[1];
        final var childRef = ToolParam.lazy("child", "Child node", "Node", () -> holder[0]);
        final var recursiveVariant = ToolParam.object("shape", "Recursive", List.of(
            ToolParam.string("value", "Value"), childRef.optional()));
        holder[0] = recursiveVariant;
        final var other = ToolParam.object("shape", "Other", List.of(ToolParam.string("x", "X")));

        final var param = ToolParam.oneOf("shape", "A shape", List.of(recursiveVariant, other));
        assertThat(param.defs()).containsKey("Node");
    }

    // --- oneOf (discriminated) ---

    private record Shape(String kind, double value) {
    }

    private static final StringParam KIND = ToolParam.string("kind", "Kind");
    private static final ToolParam<Shape> CIRCLE_VARIANT =
        ToolParam.object("shape", "Circle", List.of(KIND, ToolParam.number("r", "Radius")))
            .map(o -> new Shape("circle", o.get("r").asNumber().toNumber().doubleValue()));
    private static final ToolParam<Shape> RECT_VARIANT =
        ToolParam.object("shape", "Rect", List.of(KIND, ToolParam.number("w", "Width")))
            .map(o -> new Shape("rect", o.get("w").asNumber().toNumber().doubleValue()));
    private static final ToolParam<Shape> SHAPE_PARAM = ToolParam.oneOf("shape", "A shape", "kind", List.of(
        Map.entry("circle", CIRCLE_VARIANT),
        Map.entry("rect", RECT_VARIANT)
    ));

    @Test
    void shouldDispatchToCircleVariant() {
        final var shape = SHAPE_PARAM.extract(Json.parse("{\"shape\":{\"kind\":\"circle\",\"r\":3.0}}"));
        assertThat(shape.kind()).isEqualTo("circle");
        assertThat(shape.value()).isEqualTo(3.0);
    }

    @Test
    void shouldDispatchToRectVariant() {
        final var shape = SHAPE_PARAM.extract(Json.parse("{\"shape\":{\"kind\":\"rect\",\"w\":10.0}}"));
        assertThat(shape.kind()).isEqualTo("rect");
        assertThat(shape.value()).isEqualTo(10.0);
    }

    @Test
    void shouldThrowForUnknownDiscriminatorValue() {
        assertThatThrownBy(() -> SHAPE_PARAM.extract(Json.parse("{\"shape\":{\"kind\":\"triangle\"}}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("kind");
    }

    @Test
    void shouldInjectDiscriminatorConstIntoVariantSchemas() {
        final var schema = SHAPE_PARAM.propertySchema();
        final var circleSchema = schema.get("oneOf").asArray().element(0).asObject();
        assertThat(circleSchema.get("properties").asObject().get("kind").asObject().getString("const"))
            .isEqualTo("circle");
    }

    @Test
    void shouldThrowWhenVariantSchemaHasNoProperties() {
        final var bare = ToolParam.of("x", "x", JsonObject.builder().put("type", "string").build(), v -> v.asObject());
        assertThatThrownBy(() -> ToolParam.oneOf("x", "x", "kind", List.of(Map.entry("a", bare))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("properties");
    }

    /**
     * Same defect as the untyped {@code oneOf}: the discriminated variant is wrapped in a
     * {@code GenericParam} built from {@code propertySchema()} alone, so a variant containing a
     * {@code lazy()}-backed recursive field loses its {@code $defs} entry.
     */
    @Test
    void shouldIncludeVariantDefsInDiscriminatedOneOfSchema() {
        final ToolParam<JsonObject>[] holder = new ToolParam[1];
        final var childRef = ToolParam.lazy("child", "Child node", "Node", () -> holder[0]);
        final var recursiveVariant = ToolParam.object("shape", "Recursive", List.of(
            ToolParam.string("kind", "Kind"), childRef.optional()));
        holder[0] = recursiveVariant;

        final var param = ToolParam.<JsonObject>oneOf("shape", "A shape", "kind",
            List.of(Map.entry("recursive", recursiveVariant)));
        assertThat(param.defs()).containsKey("Node");
    }

    // --- lazy ---

    @Test
    void shouldProduceRefSchemaForLazyParam() {
        final var param = ToolParam.lazy("node", "A node", "Node", () -> ToolParam.string("node", "A node"));
        assertThat(param.propertySchema().getString("$ref")).isEqualTo("#/$defs/Node");
    }

    @Test
    void shouldNotCallSupplierUntilExtractOrDefs() {
        final var calls = new AtomicInteger();
        final var param = ToolParam.lazy("x", "x", "X", () -> {
            calls.incrementAndGet();
            return ToolParam.string("x", "x");
        });
        param.propertySchema();
        assertThat(calls.get()).isZero();
        param.extract(Json.parse("{\"x\":\"hello\"}"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void shouldCallSupplierOnlyOnce() {
        final var calls = new AtomicInteger();
        final var param = ToolParam.lazy("x", "x", "X", () -> {
            calls.incrementAndGet();
            return ToolParam.string("x", "x");
        });
        param.extract(Json.parse("{\"x\":\"a\"}"));
        param.extract(Json.parse("{\"x\":\"b\"}"));
        param.defs();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void shouldExtractValueViaLazyParam() {
        final var param = ToolParam.lazy("msg", "Message", "Msg", () -> ToolParam.string("msg", "Message"));
        assertThat(param.extract(Json.parse("{\"msg\":\"hello\"}"))).isEqualTo("hello");
    }

    @Test
    void shouldSupportSelfReferentialRecursiveSchema() {
        // node = {value: string, child?: <lazy ref to node>}
        final var value = ToolParam.string("value", "Node value");
        final ToolParam<JsonObject>[] holder = new ToolParam[1];
        final var child = ToolParam.lazy("child", "Child node", "Node", () -> holder[0]);
        final var node = ToolParam.object("node", "A tree node", List.of(value, child.optional()));
        holder[0] = node;
        final var defs = child.defs();
        assertThat(defs).containsKey("Node");
        assertThat(defs.get("Node").getString("type")).isEqualTo("object");
    }

    @Test
    void shouldReturnNullWhenOptionalLazyParamMissing() {
        final var param = ToolParam.lazy("node", "Node", "Node", () -> ToolParam.object("node", "Node", List.of()));
        assertThat(param.optional().extract(Json.parse("{}"))).isNull();
    }

    @Test
    void shouldReturnDefaultWhenOptionalLazyParamMissing() {
        final var fallback = Json.parse("{\"ok\":true}").asObject();
        final var param = ToolParam.lazy("node", "Node", "Node", () -> ToolParam.object("node", "Node", List.of()))
            .optional(fallback);
        assertThat(param.extract(Json.parse("{}"))).isSameAs(fallback);
    }

    @Test
    void shouldNotBeRequiredAfterLazyOptional() {
        final var param = ToolParam.lazy("x", "x", "X", () -> ToolParam.string("x", "x"));
        assertThat(param.isRequired()).isTrue();
        assertThat(param.optional().isRequired()).isFalse();
    }

    @Test
    void shouldExtractWhenLazyRefinePredicatePasses() {
        final var param = ToolParam.lazy("x", "x", "X", () -> ToolParam.integer("x", "x"))
            .refine(n -> n > 0, "must be positive");
        assertThat(param.extract(Json.parse("{\"x\":5}"))).isEqualTo(5);
    }

    @Test
    void shouldThrowWhenLazyRefinePredicateFails() {
        final var param = ToolParam.lazy("x", "x", "X", () -> ToolParam.integer("x", "x"))
            .refine(n -> n > 0, "must be positive");
        assertThatThrownBy(() -> param.extract(Json.parse("{\"x\":-1}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be positive");
    }

    @Test
    void shouldPreserveSchemaAfterLazyRefine() {
        final var param = ToolParam.lazy("x", "x", "X", () -> ToolParam.integer("x", "x"))
            .refine(n -> n > 0, "must be positive");
        assertThat(param.propertySchema().getString("$ref")).isEqualTo("#/$defs/X");
    }

    @Test
    void shouldCallOriginalSupplierOnceAfterRefine() {
        final var calls = new AtomicInteger();
        final var param = ToolParam.lazy("x", "x", "X", () -> {
            calls.incrementAndGet();
            return ToolParam.integer("x", "x");
        }).refine(n -> n > 0, "must be positive");
        param.extract(Json.parse("{\"x\":1}"));
        param.extract(Json.parse("{\"x\":2}"));
        assertThat(calls.get()).isEqualTo(1);
    }

    // --- ToolDef.inputSchema() with $defs ---

    @Test
    void shouldEmitDefsBlockWhenLazyParamPresent() {
        final var value = ToolParam.string("value", "Value");
        final ToolParam<JsonObject>[] holder = new ToolParam[1];
        final var nodeParam = ToolParam.lazy("node", "Node", "Node", () -> holder[0]);
        holder[0] = ToolParam.object("node", "Node", List.of(value));
        final var tool = ToolDef.of("t", "t").param(nodeParam).handle(args -> McpToolResult.text("ok"));
        final var schema = tool.inputSchema();
        assertThat(schema.has("$defs")).isTrue();
        assertThat(schema.get("$defs").asObject().has("Node")).isTrue();
    }

    @Test
    void shouldNotEmitDefsBlockWhenNoLazyParams() {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("x", "x"))
            .handle(args -> McpToolResult.text("ok"));
        assertThat(tool.inputSchema().has("$defs")).isFalse();
    }

    /**
     * For mutually recursive types (A references B via {@code lazy}, and B references A back via
     * {@code lazy}), the def contributed by the nested lazy param must still be hoisted into the root
     * {@code $defs} block, not just the top-level param's own def — otherwise the emitted schema has a
     * {@code $ref} with no matching {@code $defs} entry.
     */
    @Test
    void shouldIncludeTransitiveDefsWhenMutuallyRecursiveLazyParamsAreNested() {
        final ToolParam<JsonObject>[] aHolder = new ToolParam[1];
        final ToolParam<JsonObject>[] bHolder = new ToolParam[1];

        final var bRef = ToolParam.lazy("b", "B node", "B", () -> bHolder[0]);
        final var aParam = ToolParam.object("a", "A node", List.of(
            ToolParam.string("value", "A value"),
            bRef.optional()));
        aHolder[0] = aParam;

        final var aRef = ToolParam.lazy("a", "A node", "A", () -> aHolder[0]);
        final var bParam = ToolParam.object("b", "B node", List.of(
            ToolParam.string("value", "B value"),
            aRef.optional()));
        bHolder[0] = bParam;

        final var topLevelA = ToolParam.lazy("root", "Root A node", "A", () -> aHolder[0]);
        final var tool = ToolDef.of("t", "t").param(topLevelA).handle(args -> McpToolResult.text("ok"));

        final var schema = tool.inputSchema();
        final var defs = schema.get("$defs").asObject();
        assertThat(defs.has("A")).isTrue();
        assertThat(defs.has("B")).isTrue();
    }

    /**
     * Every {@code $defs} merge point ({@code ToolDef.schemaOf()}, {@code ObjectParam.defs()},
     * {@code LazyParam.defs()}) uses a plain {@code Map.putAll} keyed by the author-chosen
     * {@code defName} string, with no collision detection. Two independently-declared {@code lazy}
     * params that happen to share a {@code defName} but resolve to different schemas should not be
     * allowed to silently clobber each other — whichever is merged last would otherwise silently win,
     * leaving the other param's {@code $ref} pointing at the wrong schema. This asserts the desired
     * behavior (a clear failure) rather than a silent, wrong result.
     */
    @Test
    void shouldRejectConflictingDefsForSameDefName() {
        final var nodeA = ToolParam.lazy("a", "A", "Node",
            () -> ToolParam.object("a", "A", List.of(ToolParam.string("value", "A value"))));
        final var nodeB = ToolParam.lazy("b", "B", "Node",
            () -> ToolParam.object("b", "B", List.of(ToolParam.string("other", "B other"))));
        final var tool = ToolDef.of("t", "t").param(nodeA).param(nodeB).handle(args -> McpToolResult.text("ok"));

        assertThatThrownBy(tool::inputSchema)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Node");
    }

    // --- Integer extraction ---

    @Test
    void shouldExtractIntegerWrittenWithDecimalPoint() {
        assertThat(ToolParam.integer("n", "N").extract(Json.parse("{\"n\":5.0}"))).isEqualTo(5);
    }

    @Test
    void shouldRejectIntegerBeyondIntRangeInsteadOfWrapping() {
        final var param = ToolParam.integer("n", "N").min(1).max(100);

        assertThatThrownBy(() -> param.extract(Json.parse("{\"n\":4294967297}")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectFractionalInteger() {
        assertThatThrownBy(() -> ToolParam.integer("n", "N").extract(Json.parse("{\"n\":2.5}")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectHugeExponentIntegerQuickly() {
        final var param = ToolParam.integer("n", "N");

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThatThrownBy(() -> param.extract(Json.parse("{\"n\":1e999999999}")))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> param.extract(Json.parse("{\"n\":1e-999999999}")))
                .isInstanceOf(IllegalArgumentException.class);
        });
    }
}
