package dagger.android.internal.proguard;

import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XProcessingEnvConfig;
import androidx.room.compiler.processing.XProcessingStep;
import androidx.room.compiler.processing.ksp.KspBasicAnnotationProcessor;
import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;

public final class KspProguardProcessor extends KspBasicAnnotationProcessor {
  private static final XProcessingEnvConfig PROCESSING_ENV_CONFIG =
      new XProcessingEnvConfig.Builder().build();
  private XProcessingEnv env;

  private KspProguardProcessor(SymbolProcessorEnvironment symbolProcessorEnvironment) {
    super(symbolProcessorEnvironment, PROCESSING_ENV_CONFIG);
  }

  @Override
  public void initialize(XProcessingEnv env) {
    this.env = env;
  }

  @Override
  public Iterable<XProcessingStep> processingSteps() {
    return ImmutableList.of(new ProguardProcessingStep(env));
  }

  /** Provides the {@link KspProguardProcessor}. */
  @AutoService(SymbolProcessorProvider.class)
  public static final class Provider implements SymbolProcessorProvider {
    @Override
    public SymbolProcessor create(SymbolProcessorEnvironment symbolProcessorEnvironment) {
      return new KspProguardProcessor(symbolProcessorEnvironment);
    }
  }
}
