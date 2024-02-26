package dagger.android.processor;

import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XProcessingEnvConfig;
import androidx.room.compiler.processing.XProcessingStep;
import com.google.common.collect.ImmutableList;
import dagger.BindsInstance;
import dagger.Component;
import javax.inject.Singleton;

final class DelegateAndroidProcessor {
  static final XProcessingEnvConfig PROCESSING_ENV_CONFIG =
      new XProcessingEnvConfig.Builder().build();
  static final String FLAG_EXPERIMENTAL_USE_STRING_KEYS =
      "dagger.android.experimentalUseStringKeys";

  private XProcessingEnv env;

  public void initialize(XProcessingEnv env) {
    this.env = env;
  }

  public ImmutableList<XProcessingStep> processingSteps() {
    return ImmutableList.of(
        new AndroidMapKeyProcessingStep(env), new ContributesAndroidInjectorProcessingStep(env));
  }

  @Singleton
  @Component
  interface Injector {
    void inject(DelegateAndroidProcessor delegateAndroidProcessor);

    @Component.Factory
    interface Factory {
      Injector create(@BindsInstance XProcessingEnv env);
    }
  }
}
