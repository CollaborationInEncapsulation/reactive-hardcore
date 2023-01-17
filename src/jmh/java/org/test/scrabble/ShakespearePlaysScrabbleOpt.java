package org.test.scrabble;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import org.test.reactor_charcs.Flux;
import org.test.reactor_charcs.MathFlux;
import org.test.reactor_charcs.Mono;

/**
 * Embedded from https://github.com/akarnokd/akarnokd-misc/blob/master/src/jmh/java/hu/akarnokd/comparison/scrabble/ShakespearePlaysScrabbleWithReactor3Opt.java
 * <p>
 * Shakespeare plays Scrabble with Reactor.
 *
 * @author akarnokd
 * @author Stephane Maldini
 */
public class ShakespearePlaysScrabbleOpt extends ShakespearePlaysScrabble {

	public static void main(String[] args) throws Exception {
		ShakespearePlaysScrabbleOpt s = new ShakespearePlaysScrabbleOpt();
		s.init();
		System.out.println(s.measureThroughput());
	}

	@SuppressWarnings("unused")
	@Benchmark
	@BenchmarkMode(Mode.AverageTime)
	@OutputTimeUnit(TimeUnit.MILLISECONDS)
	@Warmup(iterations = 5, time = 2)
	@Measurement(iterations = 10, time = 2)
	@Fork(1)
	public List<Entry<Integer, List<String>>> measureThroughput() throws InterruptedException {

		//  to compute the score of a given word
		Function<Integer, Integer> scoreOfALetter = letter -> letterScores[letter - 'a'];

		// score of the same letters in a word
		Function<Entry<Integer, MutableLong>, Integer> letterScore =
				entry -> letterScores[entry.getKey() - 'a'] * Integer.min((int) entry.getValue()
				                                                                     .get(),
						scrabbleAvailableLetters[entry.getKey() - 'a']);

		Function<String, Flux<Integer>> toIntegerFlux = ShakespearePlaysScrabbleOpt::chars;

		// Histogram of the letters in a given word
		Function<String, Mono<Map<Integer, MutableLong>>> histoOfLetters = word -> toIntegerFlux
				.apply(word)
				.collect(HashMap::new, (Map<Integer, MutableLong> map, Integer value) -> {
							MutableLong newValue = map.get(value);
							if (newValue == null) {
								newValue = new MutableLong();
								map.put(value, newValue);
							}
							newValue.incAndSet();
						}

				);

		// number of blanks for a given letter
		Function<Entry<Integer, MutableLong>, Long> blank = entry -> Long.max(0L,
				entry.getValue()
				     .get() - scrabbleAvailableLetters[entry.getKey() - 'a']);

		// number of blanks for a given word
		Function<String, Mono<Long>> nBlanks = word -> MathFlux.sumLong(histoOfLetters.apply(word)
		                                                                              .flatMapIterable(Map::entrySet)
		                                                                              .map(blank));

		// can a word be written with 2 blanks?
		Function<String, Mono<Boolean>> checkBlanks = word -> nBlanks.apply(word)
		                                                             .map(l -> l <= 2L);

		// score taking blanks into account letterScore1
		Function<String, Mono<Integer>> score2 = word -> MathFlux.sumInt(histoOfLetters.apply(word)
		                                                                               .flatMapIterable(Map::entrySet)
		                                                                               .map(letterScore));

		// Placing the word on the board
		// Building the Fluxs of first and last letters
		Function<String, Flux<Integer>> first3 = word -> chars(word).take(3);
		Function<String, Flux<Integer>> last3 = word -> chars(word).skip(3);

		// Flux to be maxed
		Function<String, Flux<Integer>> toBeMaxed = word -> Flux.concat(first3.apply(word), last3.apply(word));

		// Bonus for double letter
		Function<String, Mono<Integer>> bonusForDoubleLetter = word -> MathFlux.max(toBeMaxed.apply(word)
		                                                                                     .map(scoreOfALetter));

		// score of the word put on the board
		Function<String, Mono<Integer>> score3 = word -> MathFlux.sumInt(score2.apply(word)
		                                                                       .concatWith(bonusForDoubleLetter.apply(
				                                                                       word)))
		                                                         .map(v -> 2 * v + (word.length() == 7 ? 50 : 0));

		Function<Function<String, Mono<Integer>>, Mono<TreeMap<Integer, List<String>>>> buildHistoOnScore =
				score -> Flux.fromIterable(shakespeareWords)
				             .filter(word -> scrabbleWords.contains(word) && checkBlanks.apply(word)
				                                                                        .block())
				             .collect(() -> new TreeMap<>(Comparator.reverseOrder()),
						             (TreeMap<Integer, List<String>> map, String word) -> {
							             Integer key = score.apply(word)
							                                .block();
							             List<String> list = map.get(key);
							             if (list == null) {
								             list = new ArrayList<>();
								             map.put(key, list);
							             }
							             list.add(word);
						             });

		// best key / value pairs
		return buildHistoOnScore.apply(score3)
		                        .flatMapIterable(Map::entrySet)
		                        .take(3)
		                        .collectList()
		                        .block();

	}



	@SuppressWarnings("unused")
	@Benchmark
	@BenchmarkMode(Mode.AverageTime)
	@OutputTimeUnit(TimeUnit.MILLISECONDS)
	@Warmup(iterations = 5, time = 2)
	@Measurement(iterations = 10, time = 2)
	@Fork(1)
	public List<Entry<Integer, List<String>>> measureThroughputOriginal() throws InterruptedException {

		//  to compute the score of a given word
		Function<Integer, Integer> scoreOfALetter = letter -> letterScores[letter - 'a'];

		// score of the same letters in a word
		Function<Entry<Integer, MutableLong>, Integer> letterScore =
				entry -> letterScores[entry.getKey() - 'a'] * Integer.min((int) entry.getValue()
								.get(),
						scrabbleAvailableLetters[entry.getKey() - 'a']);

		Function<String, org.test.reactor_original.Flux<Integer>> toIntegerFlux = ShakespearePlaysScrabbleOpt::charsOriginals;

		// Histogram of the letters in a given word
		Function<String, org.test.reactor_original.Mono<Map<Integer, MutableLong>>> histoOfLetters = word -> toIntegerFlux
				.apply(word)
				.collect(HashMap::new, (Map<Integer, MutableLong> map, Integer value) -> {
							MutableLong newValue = map.get(value);
							if (newValue == null) {
								newValue = new MutableLong();
								map.put(value, newValue);
							}
							newValue.incAndSet();
						}

				);

		// number of blanks for a given letter
		Function<Entry<Integer, MutableLong>, Long> blank = entry -> Long.max(0L,
				entry.getValue()
						.get() - scrabbleAvailableLetters[entry.getKey() - 'a']);

		// number of blanks for a given word
		Function<String, org.test.reactor_original.Mono<Long>> nBlanks = word -> org.test.reactor_original.MathFlux.sumLong(histoOfLetters.apply(word)
				.flatMapIterable(Map::entrySet)
				.map(blank));

		// can a word be written with 2 blanks?
		Function<String, org.test.reactor_original.Mono<Boolean>> checkBlanks = word -> nBlanks.apply(word)
				.map(l -> l <= 2L);

		// score taking blanks into account letterScore1
		Function<String, org.test.reactor_original.Mono<Integer>> score2 = word -> org.test.reactor_original.MathFlux.sumInt(histoOfLetters.apply(word)
				.flatMapIterable(Map::entrySet)
				.map(letterScore));

		// Placing the word on the board
		// Building the Fluxs of first and last letters
		Function<String, org.test.reactor_original.Flux<Integer>> first3 = word -> charsOriginals(word).take(3);
		Function<String, org.test.reactor_original.Flux<Integer>> last3 = word -> charsOriginals(word).skip(3);

		// Flux to be maxed
		Function<String, org.test.reactor_original.Flux<Integer>> toBeMaxed = word -> org.test.reactor_original.Flux.concat(first3.apply(word), last3.apply(word));

		// Bonus for double letter
		Function<String, org.test.reactor_original.Mono<Integer>> bonusForDoubleLetter = word -> org.test.reactor_original.MathFlux.max(toBeMaxed.apply(word)
				.map(scoreOfALetter));

		// score of the word put on the board
		Function<String, org.test.reactor_original.Mono<Integer>> score3 = word -> org.test.reactor_original.MathFlux.sumInt(score2.apply(word)
						.concatWith(bonusForDoubleLetter.apply(
								word)))
				.map(v -> 2 * v + (word.length() == 7 ? 50 : 0));

		Function<Function<String, org.test.reactor_original.Mono<Integer>>, org.test.reactor_original.Mono<TreeMap<Integer, List<String>>>> buildHistoOnScore =
				score -> org.test.reactor_original.Flux.fromIterable(shakespeareWords)
						.filter(word -> scrabbleWords.contains(word) && checkBlanks.apply(word)
								.block())
						.collect(() -> new TreeMap<>(Comparator.reverseOrder()),
								(TreeMap<Integer, List<String>> map, String word) -> {
									Integer key = score.apply(word)
											.block();
									List<String> list = map.get(key);
									if (list == null) {
										list = new ArrayList<>();
										map.put(key, list);
									}
									list.add(word);
								});

		// best key / value pairs
		return buildHistoOnScore.apply(score3)
				.flatMapIterable(Map::entrySet)
				.take(3)
				.collectList()
				.block();

	}

	static Flux<Integer> chars(String word) {
		//return Flux.range(0, word.length()).map(i -> (int)word.charAt(i));
		return new FluxCharSequence(word);
	}


	static org.test.reactor_original.Flux<Integer> charsOriginals(String word) {
		//return Flux.range(0, word.length()).map(i -> (int)word.charAt(i));
		return new FluxCharSequenceOriginal(word);
	}
}
