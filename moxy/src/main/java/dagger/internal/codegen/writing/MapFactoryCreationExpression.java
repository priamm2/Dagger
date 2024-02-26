/*
 * Copyright (C) 2015 The Dagger Authors.
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
import static dagger.internal.codegen.binding.MapKeys.getMapKeyExpression;
import static dagger.internal.codegen.binding.SourceFiles.mapFactoryClassName;
import static dagger.internal.codegen.extension.DaggerCollectors.toOptional;

import androidx.room.compiler.processing.XProcessingEnv;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import dagger.internal.codegen.base.MapType;
import dagger.internal.codegen.binding.BindingGraph;
import dagger.internal.codegen.binding.ContributionBinding;
import dagger.internal.codegen.binding.MapKeys;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.model.DependencyRequest;
import java.util.stream.Stream;

/** A factory creation expression for a multibound map. */
final class MapFactoryCreationExpression extends MultibindingFactoryCreationExpression {

  private final XProcessingEnv processingEnv;
  private final ComponentImplementation componentImplementation;
  private final BindingGraph graph;
  private final ContributionBinding binding;
  private final boolean useLazyClassKey;
  private final LazyClassKeyProviders lazyClassKeyProviders;

  @AssistedInject
  MapFactoryCreationExpression(
      @Assisted ContributionBinding binding,
      XProcessingEnv processingEnv,
      ComponentImplementation componentImplementation,
      ComponentRequestRepresentations componentRequestRepresentations,
      BindingGraph graph) {
    super(binding, componentImplementation, componentRequestRepresentations);
    this.processingEnv = processingEnv;
    this.binding = checkNotNull(binding);
    this.componentImplementation = componentImplementation;
    this.graph = graph;
    this.useLazyClassKey = MapKeys.useLazyClassKey(binding, graph);
    this.lazyClassKeyProviders =
        componentImplementation.shardImplementation(binding).getLazyClassKeyProviders();
  }

  @Override
  public CodeBlock creationExpression() {
    ClassName mapFactoryClassName = mapFactoryClassName(binding);
    CodeBlock.Builder builder = CodeBlock.builder().add("$T.", mapFactoryClassName);
    TypeName valueTypeName = TypeName.OBJECT;
    if (!useRawType()) {
      MapType mapType = MapType.from(binding.key());
      // TODO(ronshapiro): either inline this into mapFactoryClassName, or add a
      // mapType.unwrappedValueType() method that doesn't require a framework type
      valueTypeName =
          Stream.of(TypeNames.PROVIDER, TypeNames.PRODUCER, TypeNames.PRODUCED)
              .filter(mapType::valuesAreTypeOf)
              .map(mapType::unwrappedValueType)
              .collect(toOptional())
              .orElseGet(mapType::valueType)
              .getTypeName();
      builder.add(
          "<$T, $T>",
          useLazyClassKey ? TypeNames.STRING : mapType.keyType().getTypeName(),
          valueTypeName);
    }

    builder.add("builder($L)", binding.dependencies().size());

    for (DependencyRequest dependency : binding.dependencies()) {
      ContributionBinding contributionBinding = graph.contributionBinding(dependency.key());
      builder.add(
          ".put($L, $L)",
          useLazyClassKey
              ? lazyClassKeyProviders.getMapKeyExpression(dependency.key())
              : getMapKeyExpression(
                  contributionBinding, componentImplementation.name(), processingEnv),
          multibindingDependencyExpression(dependency));
    }

    return useLazyClassKey
        ? CodeBlock.of(
            "$T.<$T>of($L)",
            TypeNames.LAZY_CLASS_KEY_MAP_FACTORY,
            valueTypeName,
            builder.add(".build()").build())
        : builder.add(".build()").build();
  }

  @AssistedFactory
  static interface Factory {
    MapFactoryCreationExpression create(ContributionBinding binding);
  }
}
