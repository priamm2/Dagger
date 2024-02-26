package dagger.android.processor;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Sets.difference;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableMap;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;

import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XProcessingStep;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.squareup.javapoet.ClassName;
import java.util.Map;
import java.util.Set;

public abstract class BaseProcessingStep implements XProcessingStep {
  @Override
  public final ImmutableSet<String> annotations() {
    return annotationClassNames().stream().map(ClassName::canonicalName).collect(toImmutableSet());
  }

  @Override
  public ImmutableSet<XElement> process(
      XProcessingEnv env, Map<String, ? extends Set<? extends XElement>> elementsByAnnotation) {
    ImmutableSet.Builder<XElement> deferredElements = ImmutableSet.builder();
    inverse(elementsByAnnotation)
        .forEach(
            (element, annotations) -> {
              try {
                process(element, annotations);
              } catch (TypeNotPresentException e) {
                deferredElements.add(element);
              }
            });
    return deferredElements.build();
  }

  protected abstract void process(XElement element, ImmutableSet<ClassName> annotations);

  private ImmutableMap<XElement, ImmutableSet<ClassName>> inverse(
      Map<String, ? extends Set<? extends XElement>> elementsByAnnotation) {
    ImmutableMap<String, ClassName> annotationClassNames =
        annotationClassNames().stream()
            .collect(toImmutableMap(ClassName::canonicalName, className -> className));
    checkState(
        annotationClassNames.keySet().containsAll(elementsByAnnotation.keySet()),
        "Unexpected annotations for %s: %s",
        this.getClass().getCanonicalName(),
        difference(elementsByAnnotation.keySet(), annotationClassNames.keySet()));

    ImmutableSetMultimap.Builder<XElement, ClassName> builder = ImmutableSetMultimap.builder();
    elementsByAnnotation.forEach(
        (annotationName, elementSet) ->
            elementSet.forEach(
                element -> builder.put(element, annotationClassNames.get(annotationName))));

    return ImmutableMap.copyOf(Maps.transformValues(builder.build().asMap(), ImmutableSet::copyOf));
  }

  protected abstract Set<ClassName> annotationClassNames();
}
