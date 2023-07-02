/*
 * Copyright 2023 LibreMobileOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.libremobileos.yifan.util;

import org.tensorflow.lite.Delegate;

public class GpuDelegateFactory {
    public static Delegate get() {
        throw new UnsupportedOperationException(
                "compiled without GPU library, can't create GPU delegate");
    }

    public static boolean isSupported() {
        return false;
    }
}
