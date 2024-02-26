/*
 * Copyright (C) 2018 The Dagger Authors.
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

package dagger.internal.codegen.writing;

import static com.google.common.base.Preconditions.checkNotNull;
import static dagger.internal.codegen.writing.DelegateRequestRepresentation.instanceRequiresCast;

import androidx.room.compiler.processing.XProcessingEnv;
import com.squareup.javapoet.ClassName;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import dagger.internal.codegen.base.MapType;
import dagger.internal.codegen.binding.BindsTypeChecker;
import dagger.internal.codegen.binding.ComponentDescriptor.ComponentMethodDescriptor;
import dagger.internal.codegen.binding.ContributionBinding;
import dagger.internal.codegen.binding.FrameworkType;
import dagger.internal.codegen.javapoet.Expression;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.model.BindingKind;
import dagger.internal.codegen.model.RequestKind;

/** A binding expression that depends on a framework instance. */
final class DerivedFromFrameworkInstanceRequestRepresentation extends RequestRepresentation {
  private final ContributionBinding binding;
  private final RequestRepresentation frameworkRequestRepresentation;
  private final RequestKind requestKind;
  private final FrameworkType frameworkType;
  private final XProcessingEnv processingEnv;
  private final BindsTypeChecker bindsTypeChecker;

  @AssistedInject
  DerivedFromFrameworkInstanceRequestRepresentation(
      @Assisted ContributionBinding binding,
      @Assisted RequestRepresentation frameworkRequestRepresentation,
      @Assisted RequestKind requestKind,
      @Assisted FrameworkType frameworkType,
      XProcessingEnv processingEnv,
      BindsTypeChecker bindsTypeChecker) {
    this.binding = binding;
    this.frameworkRequestRepresentation = checkNotNull(frameworkRequestRepresentation);
    this.requestKind = requestKind;
    this.frameworkType = checkNotNull(frameworkType);
    this.processingEnv = processingEnv;
    this.bindsTypeChecker = bindsTypeChecker;
  }

  @Override
  Expression getDependencyExpression(ClassName requestingClass) {
    return getDependencyExpressionFromFrameworkExpression(
        frameworkRequestRepresentation.getDependencyExpression(requestingClass),
        requestingClass);
  }

  @Override
  Expression getDependencyExpressionForComponentMethod(
      ComponentMethodDescriptor componentMethod, ComponentImplementation component) {
    return getDependencyExpressionFromFrameworkExpression(
        frameworkRequestRepresentation
            .getDependencyExpressionForComponentMethod(componentMethod, component),
        component.name());
  }

  private Expression getDependencyExpressionFromFrameworkExpression(
      Expression frameworkExpression, ClassName requestingClass) {
    Expression expression =
        frameworkType.to(
            requestKind,
            frameworkExpression,
            processingEnv);

    // If it is a map type we need to do a raw type cast. This is because a user requested field
    // type like dagger.internal.Provider<Map<K, javax.inject.Provider<V>>> isn't always assignable
    // from something like dagger.internal.Provider<Map<K, dagger.internal.Provider<V>>> just due
    // to variance issues.
    if (MapType.isMapOfProvider(binding.contributedType())) {
      return castMapOfProvider(expression, binding);
    }

    return requiresTypeCast(expression, requestingClass)
        ? expression.castTo(binding.contributedType())
        : expression;
  }

  private Expression castMapOfProvider(Expression expression, ContributionBinding binding) {
    switch (requestKind) {
      case INSTANCE:
        return expression.castTo(binding.contributedType());
      case PROVIDER:
      case PROVIDER_OF_LAZY:
        return expression.castTo(processingEnv.requireType(TypeNames.DAGGER_PROVIDER).getRawType());
      case LAZY:
        return expression.castTo(processingEnv.requireType(TypeNames.LAZY).getRawType());
      case PRODUCER:
      case FUTURE:
        return expression.castTo(processingEnv.requireType(TypeNames.PRODUCER).getRawType());
      case PRODUCED:
        return expression.castTo(processingEnv.requireType(TypeNames.PRODUCED).getRawType());

      case MEMBERS_INJECTION: // fall through
    }
    throw new IllegalStateException("Unexpected request kind: " + requestKind);
  }

  private boolean requiresTypeCast(Expression expression, ClassName requestingClass) {
    return binding.kind().equals(BindingKind.DELEGATE)
        && requestKind.equals(RequestKind.INSTANCE)
        && instanceRequiresCast(binding, expression, requestingClass, bindsTypeChecker);
  }

  @AssistedFactory
  static interface Factory {
    DerivedFromFrameworkInstanceRequestRepresentation create(
        ContributionBinding binding,
        RequestRepresentation frameworkRequestRepresentation,
        RequestKind requestKind,
        FrameworkType frameworkType);
  }
}
