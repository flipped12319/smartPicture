/**
 * Proves that the ids seen in the space-service log are the JS-Number (double) roundings of the
 * real snowflake ids - i.e. the frontend sent a Number instead of a String.
 *
 * Snowflake ids are 19 digits (~2.1e18) which exceeds Number.MAX_SAFE_INTEGER (9007199254740991),
 * so once a value goes through a JS Number it can only land on a multiple of 256 (and often of 100
 * after toString rounding).
 */
public class ProbePrecision {
    public static void main(String[] args) {
        check("2107713929437249537", "2107713929437249500"); // 团队空间2
        check("2100208978036293634", "2100208978036293600"); // 团队空间1
    }

    private static void check(String realIdText, String observedText) {
        long real = Long.parseLong(realIdText);
        double asDouble = real;                 // exactly what `Number(id)` would produce
        long rounded = (long) asDouble;          // and what JSON/query serialisation would emit
        long observed = Long.parseLong(observedText);
        System.out.printf("real      = %d%n", real);
        System.out.printf("Number(id) = %.0f%n", asDouble);
        System.out.printf("as long    = %d%n", rounded);
        System.out.printf("observed   = %d%n", observed);
        System.out.printf("MAX_SAFE   = %d  (real > safe? %b)%n",
                9007199254740991L, real > 9007199254740991L);
        System.out.printf("MATCH      = %b%n%n", rounded == observed);
    }
}
