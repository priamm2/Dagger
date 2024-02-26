package dagger.android.processor;

import static net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.ISOLATING;

import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XProcessingStep;
import androidx.room.compiler.processing.javac.JavacBasicAnnotationProcessor;
import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor;

@IncrementalAnnotationProcessor(ISOLATING)
@AutoService(Processor.class)
public final class AndroidProcessor extends JavacBasicAnnotationProcessor {
  private final DelegateAndroidProcessor delegate = new DelegateAndroidProcessor();

  @Override
  public void initialize(XProcessingEnv env) {
    delegate.initialize(env);
  }

  @Override
  public Iterable<XProcessingStep> processingSteps() {
    return delegate.processingSteps();
  }

  @Override
  public final ImmutableSet<String> getSupportedOptions() {
    return ImmutableSet.of(DelegateAndroidProcessor.FLAG_EXPERIMENTAL_USE_STRING_KEYS);
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }
}
