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

package dagger.internal.codegen.bindinggraphvalidation;

import static androidx.room.compiler.processing.compat.XConverters.getProcessingEnv;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static dagger.internal.codegen.base.ElementFormatter.elementToString;
import static dagger.internal.codegen.base.Formatter.INDENT;
import static dagger.internal.codegen.base.Keys.isValidImplicitProvisionKey;
import static dagger.internal.codegen.base.Keys.isValidMembersInjectionKey;
import static dagger.internal.codegen.base.RequestKinds.canBeSatisfiedByProductionBinding;
import static dagger.internal.codegen.binding.DependencyRequestFormatter.DOUBLE_INDENT;
import static dagger.internal.codegen.extension.DaggerStreams.instancesOf;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.xprocessing.XTypes.isDeclared;
import static dagger.internal.codegen.xprocessing.XTypes.isWildcard;
import static javax.tools.Diagnostic.Kind.ERROR;

import androidx.room.compiler.processing.XType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.WildcardTypeName;
import dagger.internal.codegen.binding.ComponentNodeImpl;
import dagger.internal.codegen.binding.DependencyRequestFormatter;
import dagger.internal.codegen.binding.InjectBindingRegistry;
import dagger.internal.codegen.model.Binding;
import dagger.internal.codegen.model.BindingGraph;
import dagger.internal.codegen.model.BindingGraph.ComponentNode;
import dagger.internal.codegen.model.BindingGraph.DependencyEdge;
import dagger.internal.codegen.model.BindingGraph.Edge;
import dagger.internal.codegen.model.BindingGraph.MissingBinding;
import dagger.internal.codegen.model.BindingGraph.Node;
import dagger.internal.codegen.model.DaggerAnnotation;
import dagger.internal.codegen.model.DiagnosticReporter;
import dagger.internal.codegen.model.Key;
import dagger.internal.codegen.validation.DiagnosticMessageGenerator;
import dagger.internal.codegen.validation.ValidationBindingGraphPlugin;
import dagger.internal.codegen.xprocessing.XTypes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;

/** Reports errors for missing bindings. */
final class MissingBindingValidator extends ValidationBindingGraphPlugin {

  private final InjectBindingRegistry injectBindingRegistry;
  private final DependencyRequestFormatter dependencyRequestFormatter;
  private final DiagnosticMessageGenerator.Factory diagnosticMessageGeneratorFactory;

  @Inject
  MissingBindingValidator(
      InjectBindingRegistry injectBindingRegistry,
      DependencyRequestFormatter dependencyRequestFormatter,
      DiagnosticMessageGenerator.Factory diagnosticMessageGeneratorFactory) {
    this.injectBindingRegistry = injectBindingRegistry;
    this.dependencyRequestFormatter = dependencyRequestFormatter;
    this.diagnosticMessageGeneratorFactory = diagnosticMessageGeneratorFactory;
  }

  @Override
  public String pluginName() {
    return "Dagger/MissingBinding";
  }

  @Override
  public void visitGraph(BindingGraph graph, DiagnosticReporter diagnosticReporter) {
    // Don't report missing bindings when validating a full binding graph or a graph built from a
    // subcomponent.
    if (graph.isFullBindingGraph() || graph.rootComponentNode().isSubcomponent()) {
      return;
    }
    // A missing binding might exist in a different component as unused binding, thus getting
    // stripped. Therefore, full graph needs to be traversed to capture the stripped bindings.
    if (!graph.missingBindings().isEmpty()) {
      requestVisitFullGraph(graph);
    }
  }

  @Override
  public void revisitFullGraph(
      BindingGraph prunedGraph, BindingGraph fullGraph, DiagnosticReporter diagnosticReporter) {
    prunedGraph
        .missingBindings()
        .forEach(
            missingBinding -> reportMissingBinding(missingBinding, fullGraph, diagnosticReporter));
  }

  private void reportMissingBinding(
      MissingBinding missingBinding,
      BindingGraph graph,
      DiagnosticReporter diagnosticReporter) {
    diagnosticReporter.reportComponent(
        ERROR,
        graph.componentNode(missingBinding.componentPath()).get(),
        missingBindingErrorMessage(missingBinding, graph)
            + missingBindingDependencyTraceMessage(missingBinding, graph)
            + alternativeBindingsMessage(missingBinding, graph)
            + similarBindingsMessage(missingBinding, graph));
  }

  private static ImmutableSet<Binding> getSimilarTypeBindings(
      BindingGraph graph, Key missingBindingKey) {
    XType missingBindingType = missingBindingKey.type().xprocessing();
    Optional<DaggerAnnotation> missingBindingQualifier = missingBindingKey.qualifier();
    ImmutableList<TypeName> flatMissingBindingType = flattenBindingType(missingBindingType);
    if (flatMissingBindingType.size() <= 1) {
      return ImmutableSet.of();
    }
    return graph.bindings().stream()
        .filter(
            binding ->
                binding.key().qualifier().equals(missingBindingQualifier)
                    && isSimilarType(binding.key().type().xprocessing(), flatMissingBindingType))
        .collect(toImmutableSet());
  }

  /**
   * Unwraps a parameterized type to a list of TypeNames. e.g. {@code Map<Foo, List<Bar>>} to {@code
   * [Map, Foo, List, Bar]}.
   */
  private static ImmutableList<TypeName> flattenBindingType(XType type) {
    return ImmutableList.copyOf(new TypeDfsIterator(type));
  }

  private static boolean isSimilarType(XType type, List<TypeName> flatTypeNames) {
    return Iterators.elementsEqual(flatTypeNames.iterator(), new TypeDfsIterator(type));
  }

  private static TypeName getBound(WildcardTypeName wildcardType) {
    // Note: The javapoet API returns a list to be extensible, but there's currently no way to get
    // multiple bounds, and it's not really clear what we should do if there were multiple bounds
    // so we just assume there's only one for now. The javapoet API also guarantees that there will
    // always be at least one upper bound -- in the absence of an explicit upper bound the Object
    // type is used (e.g. Set<?> has an upper bound of Object).
    return !wildcardType.lowerBounds.isEmpty()
        ? getOnlyElement(wildcardType.lowerBounds)
        : getOnlyElement(wildcardType.upperBounds);
  }

  private String missingBindingErrorMessage(MissingBinding missingBinding, BindingGraph graph) {
    Key key = missingBinding.key();
    StringBuilder errorMessage = new StringBuilder();
    // Wildcards should have already been checked by DependencyRequestValidator.
    verify(!isWildcard(key.type().xprocessing()), "unexpected wildcard request: %s", key);
    // TODO(ronshapiro): replace "provided" with "satisfied"?
    errorMessage.append(key).append(" cannot be provided without ");
    if (isValidImplicitProvisionKey(key)) {
      errorMessage.append("an @Inject constructor or ");
    }
    errorMessage.append("an @Provides-"); // TODO(dpb): s/an/a
    if (allIncomingDependenciesCanUseProduction(missingBinding, graph)) {
      errorMessage.append(" or @Produces-");
    }
    errorMessage.append("annotated method.");
    if (isValidMembersInjectionKey(key) && typeHasInjectionSites(key)) {
      errorMessage.append(
          " This type supports members injection but cannot be implicitly provided.");
    }
    return errorMessage.toString();
  }

  private String missingBindingDependencyTraceMessage(
      MissingBinding missingBinding, BindingGraph graph) {
    ImmutableSet<DependencyEdge> entryPoints =
        graph.entryPointEdgesDependingOnBinding(missingBinding);
    DiagnosticMessageGenerator generator = diagnosticMessageGeneratorFactory.create(graph);
    ImmutableList<DependencyEdge> dependencyTrace =
        generator.dependencyTrace(missingBinding, entryPoints);
    StringBuilder message =
        new StringBuilder(dependencyTrace.size() * 100 /* a guess heuristic */).append("\n");
    for (DependencyEdge edge : dependencyTrace) {
      String line = dependencyRequestFormatter.format(edge.dependencyRequest());
      if (line.isEmpty()) {
        continue;
      }
      // We don't have to check for cases where component names collide since
      //  1. We always show the full classname of the component, and
      //  2. We always show the full component path at the end of the dependency trace (below).
      String componentName = String.format("[%s] ", getComponentFromDependencyEdge(edge, graph));
      message.append("\n").append(line.replace(DOUBLE_INDENT, DOUBLE_INDENT + componentName));
    }
    if (!dependencyTrace.isEmpty()) {
      generator.appendComponentPathUnlessAtRoot(message, source(getLast(dependencyTrace), graph));
    }
    message.append(
        generator.getRequestsNotInTrace(
            dependencyTrace, generator.requests(missingBinding), entryPoints));
    return message.toString();
  }

  private String alternativeBindingsMessage(
      MissingBinding missingBinding, BindingGraph graph) {
    ImmutableSet<Binding> alternativeBindings = graph.bindings(missingBinding.key());
    if (alternativeBindings.isEmpty()) {
      return "";
    }
    StringBuilder message = new StringBuilder();
    message.append("\n\nNote: ")
        .append(missingBinding.key())
        .append(" is provided in the following other components:");
    for (Binding alternativeBinding : alternativeBindings) {
      // Some alternative bindings appear multiple times because they were re-resolved in multiple
      // components (e.g. due to multibinding contributions). To avoid the noise, we only report
      // the binding where the module is contributed.
      if (alternativeBinding.contributingModule().isPresent()
          && !((ComponentNodeImpl) graph.componentNode(alternativeBinding.componentPath()).get())
              .componentDescriptor()
              .moduleTypes()
              .contains(alternativeBinding.contributingModule().get().xprocessing())) {
        continue;
      }
      message.append("\n").append(INDENT).append(asString(alternativeBinding));
    }
    return message.toString();
  }

  private String similarBindingsMessage(
      MissingBinding missingBinding, BindingGraph graph) {
    ImmutableSet<Binding> similarBindings =
        getSimilarTypeBindings(graph, missingBinding.key());
    if (similarBindings.isEmpty()) {
      return "";
    }
    StringBuilder message =
        new StringBuilder(
            "\n\nNote: A similar binding is provided in the following other components:");
    for (Binding similarBinding : similarBindings) {
      message
          .append("\n")
          .append(INDENT)
          .append(similarBinding.key())
          .append(" is provided at:")
          .append("\n")
          .append(DOUBLE_INDENT)
          .append(asString(similarBinding));
    }
    message.append("\n")
        .append(
            "(For Kotlin sources, you may need to use '@JvmSuppressWildcards' or '@JvmWildcard' if "
                + "you need to explicitly control the wildcards at a particular usage site.)");
    return message.toString();
  }

  private String asString(Binding binding) {
    return String.format(
        "[%s] %s",
        binding.componentPath().currentComponent().xprocessing().getQualifiedName(),
        binding.bindingElement().isPresent()
            ? elementToString(
                binding.bindingElement().get().xprocessing(),
                /* elideMethodParameterTypes= */ true)
            // For synthetic bindings just print the Binding#toString()
            : binding);
  }

  private boolean allIncomingDependenciesCanUseProduction(
      MissingBinding missingBinding, BindingGraph graph) {
    return graph.network().inEdges(missingBinding).stream()
        .flatMap(instancesOf(DependencyEdge.class))
        .allMatch(edge -> dependencyCanBeProduction(edge, graph));
  }

  // TODO(ronshapiro): merge with
  // ProvisionDependencyOnProduerBindingValidator.dependencyCanUseProduction
  private boolean dependencyCanBeProduction(DependencyEdge edge, BindingGraph graph) {
    Node source = graph.network().incidentNodes(edge).source();
    if (source instanceof ComponentNode) {
      return canBeSatisfiedByProductionBinding(edge.dependencyRequest().kind());
    }
    if (source instanceof Binding) {
      return ((Binding) source).isProduction();
    }
    throw new IllegalArgumentException(
        "expected a dagger.internal.codegen.model.Binding or ComponentNode: " + source);
  }

  private boolean typeHasInjectionSites(Key key) {
    return injectBindingRegistry
        .getOrFindMembersInjectionBinding(key)
        .map(binding -> !binding.injectionSites().isEmpty())
        .orElse(false);
  }

  private static String getComponentFromDependencyEdge(DependencyEdge edge, BindingGraph graph) {
    return source(edge, graph).componentPath().currentComponent().className().canonicalName();
  }

  private static Node source(Edge edge, BindingGraph graph) {
    return graph.network().incidentNodes(edge).source();
  }

  /**
   * An iterator over a list of TypeNames produced by flattening a parameterized type. e.g. {@code
   * Map<Foo, List<Bar>>} to {@code [Map, Foo, List, Bar]}.
   *
   * <p>The iterator returns the bound when encounters a wildcard type.
   */
  private static class TypeDfsIterator implements Iterator<TypeName> {
    final Deque<XType> stack = new ArrayDeque<>();

    TypeDfsIterator(XType root) {
      stack.push(root);
    }

    @Override
    public boolean hasNext() {
      return !stack.isEmpty();
    }

    @Override
    public TypeName next() {
      XType next = stack.pop();
      if (isDeclared(next)) {
        if (XTypes.isRawParameterizedType(next)) {
          XType obj = getProcessingEnv(next).requireType(TypeName.OBJECT);
          for (int i = 0; i < next.getTypeElement().getType().getTypeArguments().size(); i++) {
            stack.push(obj);
          }
        } else {
          for (XType arg : Lists.reverse(next.getTypeArguments())) {
            stack.push(arg);
          }
        }
      }
      return getBaseTypeName(next);
    }

    private static TypeName getBaseTypeName(XType type) {
      if (isDeclared(type)) {
        return type.getRawType().getTypeName();
      }
      TypeName typeName = type.getTypeName();
      if (typeName instanceof WildcardTypeName) {
        return getBound((WildcardTypeName) typeName);
      }
      return typeName;
    }
  }
}
