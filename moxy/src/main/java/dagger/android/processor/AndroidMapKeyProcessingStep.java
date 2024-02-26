package dagger.android.processor;

import static com.google.common.collect.Iterables.getOnlyElement;
import static dagger.android.processor.AndroidMapKeys.injectedTypeFromMapKey;
import static dagger.internal.codegen.xprocessing.XTypes.toStableString;

import androidx.room.compiler.processing.XAnnotation;
import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XMethodElement;
import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XType;
import androidx.room.compiler.processing.XTypeElement;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import dagger.internal.codegen.xprocessing.XElements;
import dagger.internal.codegen.xprocessing.XTypes;
import javax.tools.Diagnostic.Kind;

final class AndroidMapKeyProcessingStep extends BaseProcessingStep {
  private final XProcessingEnv processingEnv;

  AndroidMapKeyProcessingStep(XProcessingEnv processingEnv) {
    this.processingEnv = processingEnv;
  }

  @Override
  public ImmutableSet<ClassName> annotationClassNames() {
    return ImmutableSet.of(TypeNames.ANDROID_INJECTION_KEY, TypeNames.CLASS_KEY);
  }

  @Override
  public void process(XElement element, ImmutableSet<ClassName> annotationNames) {
    for (ClassName annotationName : annotationNames) {
      validateMethod(annotationName, XElements.asMethod(element));
    }
  }

  private void validateMethod(ClassName annotation, XMethodElement method) {
    if (!Sets.union(
            method.getAnnotationsAnnotatedWith(TypeNames.QUALIFIER),
            method.getAnnotationsAnnotatedWith(TypeNames.QUALIFIER_JAVAX))
        .isEmpty()) {
      return;
    }

    XType returnType = method.getReturnType();
    if (!factoryElement().getType().getRawType().isAssignableFrom(returnType.getRawType())) {
      return;
    }

    if (!Sets.union(
            method.getAnnotationsAnnotatedWith(TypeNames.SCOPE),
            method.getAnnotationsAnnotatedWith(TypeNames.SCOPE_JAVAX))
        .isEmpty()) {
      XAnnotation suppressedWarnings = method.getAnnotation(ClassName.get(SuppressWarnings.class));
      if (suppressedWarnings == null
          || !ImmutableSet.copyOf(suppressedWarnings.getAsStringList("value"))
              .contains("dagger.android.ScopedInjectorFactory")) {
        XAnnotation mapKeyAnnotation =
            getOnlyElement(method.getAnnotationsAnnotatedWith(TypeNames.MAP_KEY));
        XTypeElement mapKeyValueElement =
            processingEnv.requireTypeElement(injectedTypeFromMapKey(mapKeyAnnotation).get());
        processingEnv
            .getMessager()
            .printMessage(
                Kind.ERROR,
                String.format(
                    "%s bindings should not be scoped. Scoping this method may leak instances of"
                        + " %s.",
                    TypeNames.ANDROID_INJECTOR_FACTORY.canonicalName(),
                    mapKeyValueElement.getQualifiedName()),
                method);
      }
    }

    validateReturnType(method);

    if (method.hasAnnotation(TypeNames.BINDS) && method.getParameters().size() == 1) {
      validateMapKeyMatchesBindsParameter(annotation, method);
    }
  }

  private void validateReturnType(XMethodElement method) {
    XType returnType = method.getReturnType();
    XType requiredReturnType = injectorFactoryOf(processingEnv.getWildcardType(null, null));

    // TODO(b/311460276) use XType.isSameType when the bug is fixed.
    if (!returnType.getTypeName().equals(requiredReturnType.getTypeName())) {
      processingEnv
          .getMessager()
          .printMessage(
              Kind.ERROR,
              String.format(
                  "%s should bind %s, not %s. See https://dagger.dev/android",
                  method, toStableString(requiredReturnType), toStableString(returnType)),
              method);
    }
  }

  private void validateMapKeyMatchesBindsParameter(
      ClassName annotationName, XMethodElement method) {
    XType parameterType = getOnlyElement(method.getParameters()).getType();
    XAnnotation annotation = method.getAnnotation(annotationName);
    XType mapKeyType =
        processingEnv.requireTypeElement(injectedTypeFromMapKey(annotation).get()).getType();
    if (!XTypes.isAssignableTo(parameterType, injectorFactoryOf(mapKeyType))) {
      processingEnv
          .getMessager()
          .printMessage(
              Kind.ERROR,
              String.format(
                  "%s does not implement AndroidInjector<%s>",
                  toStableString(parameterType), toStableString(mapKeyType)),
              method,
              annotation);
    }
  }

  private XType injectorFactoryOf(XType implementationType) {
    return processingEnv.getDeclaredType(factoryElement(), implementationType);
  }

  private XTypeElement factoryElement() {
    return processingEnv.requireTypeElement(TypeNames.ANDROID_INJECTOR_FACTORY.canonicalName());
  }
}
