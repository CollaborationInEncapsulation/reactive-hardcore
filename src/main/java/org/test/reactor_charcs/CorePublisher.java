/*
 * Copyright (c) 2016-2021 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.test.reactor_charcs;

import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.test.reactor_charcs.context.Context;

/**
 * A {@link CoreSubscriber} aware publisher.
 *
 * @param <T> the {@link CoreSubscriber} data type
 * @since 3.3.0
 */
public interface CorePublisher<T> extends Publisher<T> {

	/**
	 * An internal {@link Publisher#subscribe(Subscriber)} that will bypass
	 * {@link Hooks#onLastOperator(Function)} pointcut.
	 * <p>
	 * In addition to behave as expected by {@link Publisher#subscribe(Subscriber)}
	 * in a controlled manner, it supports direct subscribe-time {@link Context} passing.
	 *
	 * @param subscriber the {@link Subscriber} interested into the published sequence
	 * @see Publisher#subscribe(Subscriber)
	 */
	void subscribe(CoreSubscriber<? super T> subscriber);

	/**
	 * Better instanceof
	 *
	 * @return all characteristic of the given source
	 */
	int characteristics();

	interface Characteristics {

		int FUSEABLE             = 0b0000_0000_0000_0000_0000_0000_0000_0001;
		int SIZED                = 0b0000_0000_0000_0000_0000_0000_0000_0010;
		int CALLABLE             = 0b0000_0000_0000_0000_0000_0000_0000_0100;
		int SCALAR               = 0b0000_0000_0000_0000_0000_0000_0000_1000;
		int OPTIMIZABLE_OPERATOR = 0b0000_0000_0000_0000_0000_0000_0001_0000;

		static boolean isCallable(int characteristics) {
			return (characteristics & CALLABLE) == CALLABLE;
		}

		static boolean isFuseable(int characteristics) {
			return (characteristics & FUSEABLE) == FUSEABLE;
		}

		static boolean isScalar(int characteristics) {
			return (characteristics & SCALAR) == SCALAR;
		}

		static boolean isOptimizableOperator(int characteristics) {
			return (characteristics & OPTIMIZABLE_OPERATOR) == OPTIMIZABLE_OPERATOR;
		}
	}
}
