/*
 * Copyright (C) 2017 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen.base;

import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableList;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.langmodel.Accessibility.isElementAccessibleFrom;
import static dagger.internal.codegen.xprocessing.XElements.closestEnclosingTypeElement;
import static javax.lang.model.element.Modifier.PRIVATE;

import androidx.room.compiler.processing.XConstructorElement;
import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XExecutableParameterElement;
import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XType;
import androidx.room.compiler.processing.XTypeElement;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.codegen.javapoet.CodeBlocks;
import dagger.internal.codegen.javapoet.TypeNames;
import java.util.Optional;
import javax.lang.model.element.Modifier;

/**
 * A source file generator that only writes the relevant code necessary for Bazel to create a
 * correct header (ABI) jar.
 */
public final class SourceFileHjarGenerator<T> extends SourceFileGenerator<T> {
  public static <T> SourceFileGenerator<T> wrap(
      SourceFileGenerator<T> delegate, XProcessingEnv processingEnv) {
    return new SourceFileHjarGenerator<>(delegate, processingEnv);
  }

  private final SourceFileGenerator<T> delegate;
  private final XProcessingEnv processingEnv;

  private SourceFileHjarGenerator(SourceFileGenerator<T> delegate, XProcessingEnv processingEnv) {
    super(delegate);
    this.delegate = delegate;
    this.processingEnv = processingEnv;
  }

  @Override
  public XElement originatingElement(T input) {
    return delegate.originatingElement(input);
  }

  @Override
  public ImmutableList<TypeSpec.Builder> topLevelTypes(T input) {
    String packageName = closestEnclosingTypeElement(originatingElement(input)).getPackageName();
    return delegate.topLevelTypes(input).stream()
        .map(completeType -> skeletonType(packageName, completeType.build()))
        .collect(toImmutableList());
  }

  private TypeSpec.Builder skeletonType(String packageName, TypeSpec completeType) {
    TypeSpec.Builder skeleton =
        classBuilder(completeType.name)
            .addSuperinterfaces(completeType.superinterfaces)
            .addTypeVariables(completeType.typeVariables)
            .addModifiers(completeType.modifiers.toArray(new Modifier[0]))
            .addAnnotations(completeType.annotations);

    if (!completeType.superclass.equals(ClassName.OBJECT)) {
      skeleton.superclass(completeType.superclass);
    }

    completeType.methodSpecs.stream()
        .filter(method -> !method.modifiers.contains(PRIVATE) || method.isConstructor())
        .map(completeMethod -> skeletonMethod(packageName, completeType, completeMethod))
        .forEach(skeleton::addMethod);

    completeType.fieldSpecs.stream()
        .filter(field -> !field.modifiers.contains(PRIVATE))
        .map(this::skeletonField)
        .forEach(skeleton::addField);

    completeType.typeSpecs.stream()
        .map(type -> skeletonType(packageName, type).build())
        .forEach(skeleton::addType);

    completeType.alwaysQualifiedNames
        .forEach(skeleton::alwaysQualify);

    return skeleton;
  }

  private MethodSpec skeletonMethod(
      String packageName, TypeSpec completeType, MethodSpec completeMethod) {
    MethodSpec.Builder skeleton =
        completeMethod.isConstructor()
            ? constructorBuilder()
            : methodBuilder(completeMethod.name).returns(completeMethod.returnType);

    if (completeMethod.isConstructor()) {
      getRequiredSuperCall(packageName, completeType)
          .ifPresent(superCall -> skeleton.addStatement("$L", superCall));
    } else if (!completeMethod.returnType.equals(TypeName.VOID)) {
      skeleton.addStatement("return $L", getDefaultValueCodeBlock(completeMethod.returnType));
    }

    return skeleton
        .addModifiers(completeMethod.modifiers)
        .addTypeVariables(completeMethod.typeVariables)
        .addParameters(completeMethod.parameters)
        .addExceptions(completeMethod.exceptions)
        .varargs(completeMethod.varargs)
        .addAnnotations(completeMethod.annotations)
        .build();
  }

  private Optional<CodeBlock> getRequiredSuperCall(String packageName, TypeSpec completeType) {
    if (completeType.superclass.equals(TypeName.OBJECT)) {
      return Optional.empty();
    }

    ClassName rawSuperClass = (ClassName) TypeNames.rawTypeName(completeType.superclass);
    XTypeElement superTypeElement =
        processingEnv.requireTypeElement(rawSuperClass.canonicalName());

    ImmutableSet<XConstructorElement> accessibleConstructors =
        superTypeElement.getConstructors().stream()
            .filter(
                constructor ->
                    // isElementAccessibleFrom doesn't take protected into account so check manually
                    constructor.isProtected()
                        || isElementAccessibleFrom(constructor, packageName))
            .collect(toImmutableSet());

    // If there's an accessible default constructor we don't need to call super() manually.
    if (accessibleConstructors.isEmpty()
            || accessibleConstructors.stream()
                .anyMatch(constructor -> constructor.getParameters().isEmpty())) {
      return Optional.empty();
    }

    return Optional.of(
        CodeBlock.of(
            "super($L)",
            CodeBlocks.makeParametersCodeBlock(
                // We just choose the first constructor (it doesn't really matter since we're just
                // trying to ensure the constructor body compiles).
                accessibleConstructors.stream().findFirst().get().getParameters().stream()
                    .map(XExecutableParameterElement::getType)
                    .map(XType::getTypeName)
                    .map(SourceFileHjarGenerator::getDefaultValueCodeBlock)
                    .collect(toImmutableList()))));
  }

  /**
   * Returns a {@link CodeBlock} containing the default value for the given {@code typeName}.
   *
   * <p>See https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html.
   */
  private static CodeBlock getDefaultValueCodeBlock(TypeName typeName) {
    if (typeName.isPrimitive()) {
      if (typeName.equals(TypeName.BOOLEAN)) {
        return CodeBlock.of("false");
      } else if (typeName.equals(TypeName.CHAR)) {
        return CodeBlock.of("'\u0000'");
      } else if (typeName.equals(TypeName.BYTE)) {
        return CodeBlock.of("0");
      } else if (typeName.equals(TypeName.SHORT)) {
        return CodeBlock.of("0");
      } else if (typeName.equals(TypeName.INT)) {
        return CodeBlock.of("0");
      } else if (typeName.equals(TypeName.LONG)) {
        return CodeBlock.of("0L");
      } else if (typeName.equals(TypeName.FLOAT)) {
        return CodeBlock.of("0.0f");
      } else if (typeName.equals(TypeName.DOUBLE)) {
        return CodeBlock.of("0.0d");
      } else {
        throw new AssertionError("Unexpected type: " + typeName);
      }
    }
    return CodeBlock.of("null");
  }

  private FieldSpec skeletonField(FieldSpec completeField) {
    return FieldSpec.builder(
            completeField.type,
            completeField.name,
            completeField.modifiers.toArray(new Modifier[0]))
        .addAnnotations(completeField.annotations)
        .build();
  }
}
