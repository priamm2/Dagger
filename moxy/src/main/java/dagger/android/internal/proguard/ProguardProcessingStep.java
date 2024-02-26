package dagger.android.internal.proguard;

import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XFiler;
import androidx.room.compiler.processing.XProcessingEnv;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.ClassName;
import dagger.android.processor.BaseProcessingStep;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Path;

public final class ProguardProcessingStep extends BaseProcessingStep {
  private final XProcessingEnv processingEnv;

  ProguardProcessingStep(XProcessingEnv processingEnv) {
    this.processingEnv = processingEnv;
  }

  static final ClassName GENERATE_RULES_ANNOTATION_NAME =
      ClassName.get("dagger.android.internal", "GenerateAndroidInjectionProguardRules");

  @Override
  public ImmutableSet<ClassName> annotationClassNames() {
    return ImmutableSet.of(GENERATE_RULES_ANNOTATION_NAME);
  }

  @Override
  public void process(XElement element, ImmutableSet<ClassName> annotationNames) {
    XFiler filer = processingEnv.getFiler();

    String errorProneRule = "-dontwarn com.google.errorprone.annotations.**\n";
    String androidInjectionKeysRule =
        "-identifiernamestring class dagger.android.internal.AndroidInjectionKeys {\n"
            + "  java.lang.String of(java.lang.String);\n"
            + "}\n";

    writeFile(filer, "com.android.tools/proguard", errorProneRule);
    writeFile(filer, "com.android.tools/r8", errorProneRule + androidInjectionKeysRule);
    writeFile(filer, "proguard", errorProneRule);
  }

  private void writeFile(XFiler filer, String intermediatePath, String contents) {
    try (OutputStream outputStream =
            filer.writeResource(
                Path.of("META-INF/" + intermediatePath + "/dagger-android.pro"),
                ImmutableList.<XElement>of(),
                XFiler.Mode.Isolating);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8))) {
      writer.write(contents);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
