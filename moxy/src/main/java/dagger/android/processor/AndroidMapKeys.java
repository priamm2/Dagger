package dagger.android.processor;

import androidx.room.compiler.processing.XAnnotation;
import androidx.room.compiler.processing.XAnnotationValue;
import java.util.Optional;

final class AndroidMapKeys {

  static Optional<String> injectedTypeFromMapKey(XAnnotation mapKey) {
    XAnnotationValue mapKeyClass = mapKey.getAnnotationValue("value");
    if (mapKeyClass.hasStringValue()) {
      return Optional.of(mapKeyClass.asString());
    } else if (mapKeyClass.hasTypeValue()) {
      return Optional.of(mapKeyClass.asType().getTypeElement().getQualifiedName());
    } else {
      return Optional.empty();
    }
  }
}
