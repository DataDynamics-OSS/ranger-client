// @imports
import java.util.Arrays;
// @class
    /** {@code PolicyResource.of("sales")} */
    public static PolicyResource of(String... values) {
        return builder().values(Arrays.asList(values)).build();
    }

    /** Matches everything except the given values. */
    public static PolicyResource excluding(String... values) {
        return builder().values(Arrays.asList(values)).excludes(true).build();
    }

    /** Recursive match (paths). */
    public static PolicyResource recursive(String... values) {
        return builder().values(Arrays.asList(values)).recursive(true).build();
    }
