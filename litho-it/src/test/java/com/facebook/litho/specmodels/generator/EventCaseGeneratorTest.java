/*
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.litho.specmodels.generator;

import static org.assertj.core.api.Java6Assertions.assertThat;

import com.facebook.litho.specmodels.internal.ImmutableList;
import com.facebook.litho.specmodels.model.ClassNames;
import com.facebook.litho.specmodels.model.EventDeclarationModel;
import com.facebook.litho.specmodels.model.EventMethod;
import com.facebook.litho.specmodels.model.SpecMethodModel;
import com.facebook.litho.specmodels.model.TypeSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import org.junit.Test;

/** Tests {@link EventCaseGenerator} */
public class EventCaseGeneratorTest {
  @Test
  public void testBasicGeneratorCase() {
    final MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("method");
    final EventDeclarationModel model =
        new EventDeclarationModel(ClassName.OBJECT, TypeName.VOID, ImmutableList.of(), null);

    EventCaseGenerator.builder()
        .contextClass(ClassNames.COMPONENT_CONTEXT)
        .eventMethodModels(
            ImmutableList.of(
                SpecMethodModel.<EventMethod, EventDeclarationModel>builder()
                    .name("event")
                    .returnTypeSpec(new TypeSpec(TypeName.VOID))
                    .typeModel(model)
                    .build()))
        .writeTo(methodBuilder);

    assertThat(methodBuilder.build().toString())
        .isEqualTo(
            "void method() {\n"
                + "  case 96891546: {\n"
                + "    java.lang.Object _event = (java.lang.Object) eventState;\n"
                + "    event(\n"
                + "          eventHandler.mHasEventDispatcher);\n"
                + "    return null;\n"
                + "  }\n"
                + "}\n");
  }
}
