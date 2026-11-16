package ca.canada.inspection.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The accepted primer hits on a set of sequences, with those sequences' descriptions: a
 * sample's contigs, or the reads a FASTQ-mode search looks at. What the amplicon calling
 * works on; a {@link Sample} holds the hits on its own sequences, and the read searches
 * make one of their own, without making a sample of their reads.
 */
public final class Hits {
    private final HashMap<String, ArrayList<BlastResult>> byOligo = new HashMap<>();
    private final HashMap<String, String> descriptions = new HashMap<>();
    /** The circular sequences, each with its length as a circle ({@link ca.canada.inspection.util.Circles}). */
    private final HashMap<String, Integer> circles = new HashMap<>();
    /** Results worked out from the hits and descriptions (the products), until either changes. */
    private final HashMap<Key<?>, Object> derived = new HashMap<>();

    /** Adds an accepted hit of oligo {@code oligoId}. */
    public synchronized void add(String oligoId, BlastResult hit) {
        byOligo.computeIfAbsent(oligoId, ignored -> new ArrayList<>()).add(hit);
        derived.clear();
    }

    /** Records the description of sequence {@code id} (the FASTA header after the ID). */
    public synchronized void describe(String id, String description) {
        descriptions.put(id, description);
        derived.clear();
    }

    /** Records that sequence {@code id} is a circle of {@code length} bases: its products may cross its origin. */
    public synchronized void circular(String id, int length) {
        circles.put(id, length);
        derived.clear();
    }

    /** The circular sequences' lengths as circles, by sequence ID; a copy. */
    public synchronized Map<String, Integer> circles() {
        return Map.copyOf(circles);
    }

    /** The hits of each oligo, by oligo ID; a copy, in no particular order. */
    public synchronized Map<String, List<BlastResult>> byOligo() {
        HashMap<String, List<BlastResult>> copy = new HashMap<>();
        byOligo.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    /** Sequence descriptions, by sequence ID; a copy. */
    public synchronized Map<String, String> descriptions() {
        return Map.copyOf(descriptions);
    }

    /** The hits of {@code oligoIds}, in that order. */
    public synchronized List<BlastResult> of(Collection<String> oligoIds) {
        List<BlastResult> hits = new ArrayList<>();
        for (String id : oligoIds) {
            List<BlastResult> ofId = byOligo.get(id);
            if (ofId != null) {
                hits.addAll(ofId);
            }
        }
        return hits;
    }

    /** The hits {@code keep} keeps, with every description. */
    public synchronized Hits filtered(Predicate<BlastResult> keep) {
        Hits kept = new Hits();
        kept.descriptions.putAll(descriptions);
        kept.circles.putAll(circles);
        byOligo.forEach((oligo, hits) -> hits.stream().filter(keep).forEach(hit -> kept.add(oligo, hit)));
        return kept;
    }

    /**
     * What a result worked out from the hits is found under: a key of its own type, so that what
     * is kept is given back as what was put, without a cast where it is asked for.
     *
     * @param <T> the result's type
     */
    public interface Key<T> {
    }

    /**
     * What {@code compute} gives for {@code key}, worked out once while the hits and
     * descriptions stay as they are: several steps of a run ask for the same products.
     */
    public synchronized <T> T derived(Key<T> key, Supplier<T> compute) {
        Object value = derived.get(key);
        if (value == null) {
            value = compute.get();
            derived.put(key, value);
        }
        @SuppressWarnings("unchecked") // put under its key, of its key's type
        T result = (T) value;
        return result;
    }
}
