package dagger.android.internal.proguard;

import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XProcessingStep;
import androidx.room.compiler.processing.javac.JavacBasicAnnotationProcessor;
import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;

@AutoService(Processor.class)
public final class ProguardProcessor extends JavacBasicAnnotationProcessor {
  private XProcessingEnv env;

  @Override
  public void initialize(XProcessingEnv env) {
    this.env = env;
  }

  @Override
  public Iterable<XProcessingStep> processingSteps() {
    return ImmutableList.of(new ProguardProcessingStep(env));
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }
}
