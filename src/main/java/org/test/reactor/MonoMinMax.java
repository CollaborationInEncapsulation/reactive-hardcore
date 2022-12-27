/*
 * Copyright (c) 2017-2021 VMware Inc. or its affiliates, All Rights Reserved.
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

package org.test.reactor;

import java.util.Comparator;

import org.reactivestreams.Publisher;

/**
 * Computes the maximum or minimum of source items and returns the result.
 *
 * @param <T> the input value type
 */
final class MonoMinMax<T> extends MonoFromFluxOperator<T, T> implements Fuseable {

	final Comparator<? super T> comparator;

	final int comparisonMultiplier;

	MonoMinMax(Publisher<? extends T> source, Comparator<? super T> comparator, int comparisonMultiplier) {
		super(Flux.from(source));
		this.comparator = comparator;
		this.comparisonMultiplier = comparisonMultiplier;
	}

	static final int CHS =
			Characteristics.FUSEABLE | Characteristics.OPTIMIZABLE_OPERATOR;

	@Override
	public int characteristics() {
		return CHS;
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> s) {
		return new MinMaxSubscriber<T>(s, comparator, comparisonMultiplier);
	}

	static final class MinMaxSubscriber<T> extends MathSubscriber<T, T> {

		final Comparator<? super T> comparator;

		final int comparisonMultiplier;

		T result;

		MinMaxSubscriber(CoreSubscriber<? super T> actual, Comparator<? super T> comparator, int comparisonMultiplier) {
			super(actual);
			this.comparator = comparator;
			this.comparisonMultiplier = comparisonMultiplier;
		}

		@Override
		protected void updateResult(T newValue) {
			T r = result;
			if (r == null || comparator.compare(newValue, result) * comparisonMultiplier > 0) {
				r = newValue;
			}
			result = r;
		}

		@Override
		protected T result() {
			return result;
		}

		@Override
		protected void reset() {
			result = null;
		}
	}
}