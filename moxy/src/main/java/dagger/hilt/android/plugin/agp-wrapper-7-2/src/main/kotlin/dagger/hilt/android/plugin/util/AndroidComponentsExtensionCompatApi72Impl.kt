/*
 * Copyright (C) 2022 The Dagger Authors.
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

package dagger.hilt.android.plugin.util

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasAndroidTest
import org.gradle.api.Project

class AndroidComponentsExtensionCompatApi72Impl(
  private val project: Project
) : AndroidComponentsExtensionCompat {

  override fun onAllVariants(block: (ComponentCompat) -> Unit) {
    val actual = project.extensions.getByType(AndroidComponentsExtension::class.java)
    actual.onVariants { variant ->
      block.invoke(ComponentCompatApi72Impl(variant))

      (variant as? HasAndroidTest)?.androidTest?.let { block.invoke(ComponentCompatApi72Impl(it)) }

      variant.unitTest?.let { block.invoke(ComponentCompatApi72Impl(it)) }
    }
  }
}
