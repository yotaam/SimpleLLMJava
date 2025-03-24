package com.example.gpt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;


public class BytePairEncoding {

    public static Map<Integer, String> bytesToUnicode() {
        List<Integer> bs = new ArrayList<>();
        // add ranges of bytes that correspond to printable characters
        for (int i = (int) '!'; i <= (int) '~'; i++) bs.add(i);
        for (int i = (int) '¡'; i <= (int) '¬'; i++) bs.add(i);
        for (int i = (int) '®'; i <= (int) 'ÿ'; i++) bs.add(i);

        List<Integer> cs = new ArrayList<>(bs);
        int n = 0;

        // handle other bytes that aren't already in the list
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n++;
            }
        }

        // create a map of byte -> unicode character
        Map<Integer, String> byteToUnicode = new HashMap<>();
        for (int i = 0; i < bs.size(); i++) {
            byteToUnicode.put(bs.get(i), String.valueOf((char) (int) cs.get(i)));
        }

        return byteToUnicode;
    }

    public static Set<Pair<String, String>> getPairs(String[] word) {
        Set<Pair<String, String>> pairs = new HashSet<>();
        String prevChar = word[0]; 
        for (int i = 1; i < word.length; i++) {
            pairs.add(new Pair<>(prevChar, word[i])); 
            prevChar = word[i]; 
        }
        return pairs;
    }

    public static class Encoder {
        private final Map<String, Integer> encoder; 
        private final Map<Integer, String> decoder; 
        private final Map<Integer, String> byteEncoder; 
        private final Map<String, Integer> byteDecoder; 
        private final Map<Pair<String, String>, Integer> bpeRanks;
        private final Map<String, String> cache = new HashMap<>(); 
        private final Pattern pattern; 

        public Encoder(Map<String, Integer> encoder, List<Pair<String, String>> bpeMerges, String errors) {
            this.encoder = encoder;
            this.decoder = encoder.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey)); // reverse the encoder map
            this.byteEncoder = bytesToUnicode(); 
            this.byteDecoder = byteEncoder.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey)); // reverse byte mapping
            this.bpeRanks = new HashMap<>();
            for (int i = 0; i < bpeMerges.size(); i++) {
                bpeRanks.put(bpeMerges.get(i), i); // create rank for each pair
            }
            // define regex pattern for splitting text into tokens
            this.pattern = Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+");
        }

        public String bpe(String token) {
            if (cache.containsKey(token)) return cache.get(token);
            String[] word = token.split(""); 
            Set<Pair<String, String>> pairs = getPairs(word);

            if (pairs.isEmpty()) return token; 

            while (true) {
                // find the pair with the lowest rank
                Pair<String, String> bigram = pairs.stream()
                        .min(Comparator.comparingInt(p -> bpeRanks.getOrDefault(p, Integer.MAX_VALUE)))
                        .orElse(null);

                if (bigram == null || !bpeRanks.containsKey(bigram)) break; 

                String first = bigram.first, second = bigram.second;
                List<String> newWord = new ArrayList<>();
                for (int i = 0; i < word.length; ) {
                    // find the first occurrence of the pair
                    int j = indexOf(word, first, i);
                    if (j == -1) {
                        newWord.addAll(Arrays.asList(word).subList(i, word.length));
                        break;
                    }
                    newWord.addAll(Arrays.asList(word).subList(i, j)); 
                    if (j < word.length - 1 && word[j].equals(first) && word[j + 1].equals(second)) {
                        newWord.add(first + second); 
                        i = j + 2;
                    } else {
                        newWord.add(word[j]); 
                        i = j + 1;
                    }
                }
                word = newWord.toArray(new String[0]); 
                pairs = getPairs(word); 
            }

            String result = String.join(" ", word); 
            cache.put(token, result); 
            return result;
        }

        // Encode text into BPE tokens
        public List<Integer> encode(String text) {
            List<Integer> bpeTokens = new ArrayList<>();
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String token = matcher.group();
                // convert token to bytes and apply BPE
                token = token.chars()
                        .mapToObj(c -> byteEncoder.get(c))
                        .collect(Collectors.joining());
                String[] splitBpeTokens = bpe(token).split(" "); 
                for (String bpeToken : splitBpeTokens) {
                    bpeTokens.add(encoder.get(bpeToken)); 
                }
            }
            return bpeTokens; 
        }

        public String decode(List<Integer> tokens) {

            StringBuilder bpeTokensBuilder = new StringBuilder();
            for (Integer token : tokens) {
                String bpeToken = decoder.get(token);
                if (bpeToken == null) {
                    System.err.println("Warning: Token ID " + token + " not found in decoder.");
                    continue;
                }
                bpeTokensBuilder.append(bpeToken);
            }
 
            String bpeText = bpeTokensBuilder.toString();


            List<Byte> byteList = new ArrayList<>();
            for (int i = 0; i < bpeText.length(); i++) {
                String s = String.valueOf(bpeText.charAt(i));
                Integer byteValue = byteDecoder.get(s);
                if (byteValue == null) {
                    System.err.println("Warning: Character '" + s + "' not found in byteDecoder.");
                    continue;
                }
                byteList.add(byteValue.byteValue());
            }

            byte[] byteArray = new byte[byteList.size()];
            for (int i = 0; i < byteList.size(); i++) {
                byteArray[i] = byteList.get(i);
            }
            String text = new String(byteArray, StandardCharsets.UTF_8);

            return text;
        }
    }
    private static int indexOf(String[] array, String element, int startIndex) {
        for (int i = startIndex; i < array.length; i++) {
            if (array[i].equals(element)) {
                return i;
            }
        }
        return -1;
    }    

    // utility method to load an encoder from files
    public static Encoder getEncoder(String modelName, String modelsDir) throws IOException {
        // read the encoder.json file
        BufferedReader encoderReader = new BufferedReader(new FileReader(modelsDir + "/" + modelName + "/encoder.json"));
        Map<String, Integer> encoder = new HashMap<>(new Gson().fromJson(encoderReader, new TypeToken<Map<String, Integer>>() {}.getType()));
        encoderReader.close();

        // read the vocab.bpe file
        BufferedReader vocabReader = new BufferedReader(new FileReader(modelsDir + "/" + modelName + "/vocab.bpe"));
        List<Pair<String, String>> bpeMerges = vocabReader.lines()
                .skip(1) 
                .filter(line -> !line.isEmpty()) 
                .map(line -> {
                    String[] split = line.split(" ");
                    return new Pair<>(split[0], split[1]); 
                })
                .collect(Collectors.toList());
        vocabReader.close();

        return new Encoder(encoder, bpeMerges, "replace"); 
    }

    public static class Pair<F, S> {
        public final F first;
        public final S second;

        public Pair(F first, S second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Pair<?, ?> pair = (Pair<?, ?>) o;
            return Objects.equals(first, pair.first) && Objects.equals(second, pair.second);
        }
    }
}
