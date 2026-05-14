/*
 * Copyright 2019. Santanu Sinha
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and limitations
 * under the License.
 */

package io.appform.hope.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Collections;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.appform.hope.core.Evaluatable;
import io.appform.hope.core.exceptions.errorstrategy.InjectValueErrorHandlingStrategy;
import io.appform.hope.core.functions.FunctionRegistry;
import io.appform.hope.core.visitors.Evaluator;
import io.appform.hope.lang.parser.HopeParser;
import lombok.SneakyThrows;
import lombok.val;

/**
 * Tests that verify:
 * 1. explodeArray correctly resolves arrays via JsonPointer (the fixed code path).
 * 2. The per-evaluation cache in EvaluationContext is actually used by explodeArray —
 * i.e. a given path is resolved only once even when referenced by multiple arr.*
 * calls sharing the same EvaluationContext.
 */
class ExplodeArrayCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Configuration JSONPATH_CONFIG = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build();

    private final FunctionRegistry functionRegistry;
    private final Evaluator evaluator;

    ExplodeArrayCacheTest() {
        this.functionRegistry = new FunctionRegistry();
        functionRegistry.discover(Collections.emptyList());
        this.evaluator = new Evaluator(new InjectValueErrorHandlingStrategy());
    }

    // -------------------------------------------------------------------------
    // 1. Correctness: arr.* functions via JsonPointer syntax
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("arrFunctionsViaJsonPointer")
    @SneakyThrows
    void arrFunctionsWorkWithJsonPointer(String json, String rule, boolean expected) {
        val node = MAPPER.readTree(json);
        assertEquals(expected,
                     evaluator.evaluate(parse(rule), node),
                     "rule [" + rule + "] on [" + json + "]");
    }

    static Stream<Arguments> arrFunctionsViaJsonPointer() {
        return Stream.of(
                         // arr.in — needle in array
                         Arguments.of("{\"haystack\": [1, 2, 3], \"needle\": 2}",
                                      "arr.in(\"/needle\", \"/haystack\") == true",
                                      true),
                         Arguments.of("{\"haystack\": [1, 2, 3], \"needle\": 99}",
                                      "arr.in(\"/needle\", \"/haystack\") == true",
                                      false),
                         Arguments.of("{\"haystack\": [\"a\", \"b\", \"c\"], \"needle\": \"b\"}",
                                      "arr.in(\"/needle\", \"/haystack\") == true",
                                      true),

                         // arr.not_in
                         Arguments.of("{\"haystack\": [1, 2, 3], \"needle\": 99}",
                                      "arr.not_in(\"/needle\", \"/haystack\") == true",
                                      true),
                         Arguments.of("{\"haystack\": [1, 2, 3], \"needle\": 2}",
                                      "arr.not_in(\"/needle\", \"/haystack\") == true",
                                      false),

                         // arr.contains_any — at least one element of lhs array is in rhs array
                         Arguments.of("{\"val\": [1, 2, 4, 8, 16]}",
                                      "arr.contains_any(\"/val\", [2, 3]) == true",
                                      true),
                         Arguments.of("{\"val\": [1, 2, 4, 8, 16]}",
                                      "arr.contains_any(\"/val\", [9, 7]) == true",
                                      false),

                         // arr.contains_all
                         Arguments.of("{\"val\": [1, 2, 4, 8, 16]}",
                                      "arr.contains_all(\"/val\", [2, 4]) == true",
                                      true),
                         Arguments.of("{\"val\": [1, 2, 4, 8, 16]}",
                                      "arr.contains_all(\"/val\", [2, 3]) == true",
                                      false),

                         // arr.len
                         Arguments.of("{\"items\": [10, 20, 30]}",
                                      "arr.len(\"/items\") == 3",
                                      true),
                         Arguments.of("{\"items\": []}",
                                      "arr.len(\"/items\") == 0",
                                      true),

                         // arr.is_empty
                         Arguments.of("{\"items\": []}",
                                      "arr.is_empty(\"/items\") == true",
                                      true),
                         Arguments.of("{\"items\": [1]}",
                                      "arr.is_empty(\"/items\") == true",
                                      false)
        );
    }

    // -------------------------------------------------------------------------
    // 2. Cache-hit proof: JsonPath — same path resolved only once across two
    //    arr.* calls that share an EvaluationContext.
    // -------------------------------------------------------------------------

    @Test
    @SneakyThrows
    void jsonPathArrayReadCachedAcrossMultipleArrCalls() {
        val node = MAPPER.readTree("{\"tags\": [\"a\", \"b\", \"c\"]}");
        val jsonCtx = JsonPath.using(JSONPATH_CONFIG).parse(node);

        val ctx = Evaluator.EvaluationContext.builder()
                .jsonContext(jsonCtx)
                .rootNode(node)
                .evaluator(evaluator)
                .build();

        // Two arr.len calls referencing the same $.tags path share one context.
        // After both evaluate, the cache must hold exactly one entry for $.tags.
        val rule1 = parse("arr.len(\"$.tags\") == 3");
        val rule2 = parse("arr.len(\"$.tags\") == 3");

        val logicEvaluator = new Evaluator.LogicEvaluator(ctx);
        assertTrue(rule1.accept(logicEvaluator));
        assertTrue(rule2.accept(logicEvaluator));

        assertEquals(1,
                     ctx.getJsonPathEvalCache().size(),
                     "$.tags should be cached after the first evaluation; second call must hit the cache");
    }

    // -------------------------------------------------------------------------
    // 3. Cache-hit proof: JsonPointer — same pointer resolved only once across
    //    two arr.* calls that share an EvaluationContext.
    // -------------------------------------------------------------------------

    @Test
    @SneakyThrows
    void jsonPointerArrayReadCachedAcrossMultipleArrCalls() {
        val node = MAPPER.readTree("{\"tags\": [\"a\", \"b\", \"c\"]}");
        val jsonCtx = JsonPath.using(JSONPATH_CONFIG).parse(node);

        val ctx = Evaluator.EvaluationContext.builder()
                .jsonContext(jsonCtx)
                .rootNode(node)
                .evaluator(evaluator)
                .build();

        // Two arr.len calls referencing the same /tags pointer share one context.
        // After both evaluate, the cache must hold exactly one entry for /tags.
        val rule1 = parse("arr.len(\"/tags\") == 3");
        val rule2 = parse("arr.len(\"/tags\") == 3");

        val logicEvaluator = new Evaluator.LogicEvaluator(ctx);
        assertTrue(rule1.accept(logicEvaluator));
        assertTrue(rule2.accept(logicEvaluator));

        assertEquals(1,
                     ctx.getJsonPointerEvalCache().size(),
                     "/tags should be cached after the first evaluation; second call must hit the cache");
    }

    @SneakyThrows
    private Evaluatable parse(String rule) {
        return new HopeParser(new StringReader(rule)).parse(functionRegistry);
    }
}
