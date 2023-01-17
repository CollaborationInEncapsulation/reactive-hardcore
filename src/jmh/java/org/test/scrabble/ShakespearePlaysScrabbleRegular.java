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
import org.test.reactor_charcs.Mono;

/**
 * Embedded from https://github.com/akarnokd/akarnokd-misc/blob/master/src/jmh/java/hu/akarnokd/comparison/scrabble/ShakespearePlaysScrabbleWithReactor3.java
 * <p>
 * Shakespeare plays Scrabble with Reactor.
 *
 * @author José
 * @author akarnokd
 * @author Stephane Maldini
 */
public class ShakespearePlaysScrabbleRegular extends ShakespearePlaysScrabble {

	public static void main(String[] args) throws Exception {

		ShakespearePlaysScrabbleRegular s = new ShakespearePlaysScrabbleRegular();
		s.init();
		for(;;) {
			System.out.println(s.measureThroughput());
		}
	}

	@SuppressWarnings("unused")
	@Benchmark
	@BenchmarkMode(Mode.AverageTime)
	@OutputTimeUnit(TimeUnit.MILLISECONDS)
	@Warmup(iterations = 5, time = 2)
	@Measurement(iterations = 10, time = 2)
	@Fork(1)
	public List<Entry<Integer, List<String>>> measureThroughput() throws InterruptedException {

		// Function to compute the score of a given word
		Function<Integer, Mono<Integer>> scoreOfALetter = letter -> Mono.just(letterScores[letter - 'a']);

		// score of the same letters in a word
		Function<Entry<Integer, LongWrapper>, Mono<Integer>> letterScore =
				entry -> Mono.just(letterScores[entry.getKey() - 'a'] * Integer.min((int) entry.getValue()
				                                                                               .get(),
						scrabbleAvailableLetters[entry.getKey() - 'a']));

		Function<String, Flux<Integer>> toIntegerStream = string -> Flux.fromIterable(iterableOf(string.chars()
		                                                                                               .boxed()
		                                                                                               .spliterator()));

		// Histogram of the letters in a given word
		Function<String, Mono<Map<Integer, LongWrapper>>> histoOfLetters =
				word -> toIntegerStream.apply(word)
				                       .collect(HashMap::new, (Map<Integer, LongWrapper> map, Integer value) -> {
							                       LongWrapper newValue = map.get(value);
							                       if (newValue == null) {
								                       newValue = LongWrapper.zero;
							                       }
							                       map.put(value, newValue.incAndSet());
						                       }

				                       );

		// number of blanks for a given letter
		Function<Entry<Integer, LongWrapper>, Mono<Long>> blank = entry -> Mono.just(Long.max(0L,
				entry.getValue()
				     .get() - scrabbleAvailableLetters[entry.getKey() - 'a']));

		// number of blanks for a given word
		Function<String, Mono<Long>> nBlanks = word -> histoOfLetters.apply(word)
		                                                             .flatMapIterable(Map::entrySet)
		                                                             .concatMap(blank, 32)
		                                                             .reduce(Long::sum);

		// can a word be written with 2 blanks?
		Function<String, Mono<Boolean>> checkBlanks = word -> nBlanks.apply(word)
		                                                             .flatMap(l -> Mono.just(l <= 2L));

		// score taking blanks into account letterScore1
		Function<String, Mono<Integer>> score2 = word -> histoOfLetters.apply(word)
		                                                               .flatMapIterable(Map::entrySet)
		                                                               .concatMap(letterScore, 32)
		                                                               .reduce(Integer::sum);

		// Placing the word on the board
		// Building the streams of first and last letters
		Function<String, Flux<Integer>> first3 = word -> Flux.fromIterable(iterableOf(word.chars()
		                                                                                  .boxed()
		                                                                                  .limit(3)
		                                                                                  .spliterator()));
		Function<String, Flux<Integer>> last3 = word -> Flux.fromIterable(iterableOf(word.chars()
		                                                                                 .boxed()
		                                                                                 .skip(3)
		                                                                                 .spliterator()));

		// Stream to be maxed
		Function<String, Flux<Integer>> toBeMaxed = word -> first3.apply(word)
		                                                          .concatWith(last3.apply(word));

		// Bonus for double letter
		Function<String, Mono<Integer>> bonusForDoubleLetter = word -> toBeMaxed.apply(word)
		                                                                        .concatMap(scoreOfALetter, 32)
		                                                                        .reduce(Integer::max);

		// score of the word put on the board
		Function<String, Mono<Integer>> score3 = word -> Flux.just(score2.apply(word),
				score2.apply(word),
				bonusForDoubleLetter.apply(word),
				bonusForDoubleLetter.apply(word),
				Flux.just(word.length() == 7 ? 50 : 0))
		                                                     .concatMap(Function.identity(), 32)
		                                                     .reduce(Integer::sum);

		Function<Function<String, Mono<Integer>>, Mono<TreeMap<Integer, List<String>>>> buildHistoOnScore =
				score -> Flux.fromIterable(shakespeareWords)
				             .filter(it -> scrabbleWords.contains(it) && checkBlanks.apply(it).block())
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

		// Function to compute the score of a given word
		Function<Integer, org.test.reactor_original.Mono<Integer>> scoreOfALetter = letter -> org.test.reactor_original.Mono.just(letterScores[letter - 'a']);

		// score of the same letters in a word
		Function<Entry<Integer, LongWrapper>, org.test.reactor_original.Mono<Integer>> letterScore =
				entry -> org.test.reactor_original.Mono.just(letterScores[entry.getKey() - 'a'] * Integer.min((int) entry.getValue()
								.get(),
						scrabbleAvailableLetters[entry.getKey() - 'a']));

		Function<String, org.test.reactor_original.Flux<Integer>> toIntegerStream = string -> org.test.reactor_original.Flux.fromIterable(iterableOf(string.chars()
				.boxed()
				.spliterator()));

		// Histogram of the letters in a given word
		Function<String, org.test.reactor_original.Mono<Map<Integer, LongWrapper>>> histoOfLetters =
				word -> toIntegerStream.apply(word)
						.collect(HashMap::new, (Map<Integer, LongWrapper> map, Integer value) -> {
									LongWrapper newValue = map.get(value);
									if (newValue == null) {
										newValue = LongWrapper.zero;
									}
									map.put(value, newValue.incAndSet());
								}

						);

		// number of blanks for a given letter
		Function<Entry<Integer, LongWrapper>, org.test.reactor_original.Mono<Long>> blank = entry -> org.test.reactor_original.Mono.just(Long.max(0L,
				entry.getValue()
						.get() - scrabbleAvailableLetters[entry.getKey() - 'a']));

		// number of blanks for a given word
		Function<String, org.test.reactor_original.Mono<Long>> nBlanks = word -> histoOfLetters.apply(word)
				.flatMapIterable(Map::entrySet)
				.concatMap(blank, 32)
				.reduce(Long::sum);

		// can a word be written with 2 blanks?
		Function<String, org.test.reactor_original.Mono<Boolean>> checkBlanks = word -> nBlanks.apply(word)
				.flatMap(l -> org.test.reactor_original.Mono.just(l <= 2L));

		// score taking blanks into account letterScore1
		Function<String, org.test.reactor_original.Mono<Integer>> score2 = word -> histoOfLetters.apply(word)
				.flatMapIterable(Map::entrySet)
				.concatMap(letterScore, 32)
				.reduce(Integer::sum);

		// Placing the word on the board
		// Building the streams of first and last letters
		Function<String, org.test.reactor_original.Flux<Integer>> first3 = word -> org.test.reactor_original.Flux.fromIterable(iterableOf(word.chars()
				.boxed()
				.limit(3)
				.spliterator()));
		Function<String, org.test.reactor_original.Flux<Integer>> last3 = word -> org.test.reactor_original.Flux.fromIterable(iterableOf(word.chars()
				.boxed()
				.skip(3)
				.spliterator()));

		// Stream to be maxed
		Function<String, org.test.reactor_original.Flux<Integer>> toBeMaxed = word -> first3.apply(word)
				.concatWith(last3.apply(word));

		// Bonus for double letter
		Function<String, org.test.reactor_original.Mono<Integer>> bonusForDoubleLetter = word -> toBeMaxed.apply(word)
				.concatMap(scoreOfALetter, 32)
				.reduce(Integer::max);

		// score of the word put on the board
		Function<String, org.test.reactor_original.Mono<Integer>> score3 = word -> org.test.reactor_original.Flux.just(score2.apply(word),
						score2.apply(word),
						bonusForDoubleLetter.apply(word),
						bonusForDoubleLetter.apply(word),
						org.test.reactor_original.Flux.just(word.length() == 7 ? 50 : 0))
				.concatMap(Function.identity(), 32)
				.reduce(Integer::sum);

		Function<Function<String, org.test.reactor_original.Mono<Integer>>, org.test.reactor_original.Mono<TreeMap<Integer, List<String>>>> buildHistoOnScore =
				score -> org.test.reactor_original.Flux.fromIterable(shakespeareWords)
						.filter(it -> scrabbleWords.contains(it) && checkBlanks.apply(it).block())
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
}
