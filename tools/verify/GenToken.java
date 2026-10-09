import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Generates JWTs for the verification harnesses in this directory.
 *
 * Why this exists: the verification scripts need to call the services as specific users, and the
 * everyday login flow only needs a password. Signing tokens directly with the shared secret is
 * faster and does not require knowing anyone's password.
 *
 * ⚠️ THE SECRET IS **NOT** IN THIS FILE, ON PURPOSE, AND MUST NEVER COME BACK.
 *   An earlier version hard-coded the real signing secret as a default here. That file is
 *   tracked by git, so the secret shipped to GitHub — and since this secret is what all three
 *   services use to verify tokens, anyone who could read the repo could mint a valid **admin**
 *   token and call every internal API. The secret had to be rotated.
 *   So: pass it in explicitly, every time. If you are tempted to add a default "for convenience",
 *   re-read this paragraph.
 *
 * Where to get the secret: `jwt.secret` in picture-backend/src/main/resources/application.yml
 * (that file is git-ignored, and the same value is used by picture-user-service and
 * picture-space-service — user-service signs, the others verify).
 *
 * Build & run (JDK 11 + the three jjwt jars):
 *   javac -cp "&lt;m2&gt;\io\jsonwebtoken\jjwt-api\0.11.5\jjwt-api-0.11.5.jar" GenToken.java
 *   java  -Dsecret=&lt;the value from application.yml&gt; -cp ".;&lt;jjwt-api&gt;;&lt;jjwt-impl&gt;;&lt;jjwt-jackson&gt;;&lt;jackson-databind&gt;;&lt;jackson-core&gt;;&lt;jackson-annotations&gt;" GenToken &lt;userId&gt; [...]
 *
 * Output: "&lt;userId&gt;=&lt;token&gt;" per line, which is exactly the format the harnesses read from
 * .tmp-dbdump\tokens.txt. **Do not commit that file** (it is git-ignored).
 */
public class GenToken {

    /**
     * The secret must be supplied via {@code -Dsecret=...}. See the class comment for why there is
     * deliberately no default here.
     */
    private static final String SECRET_PROPERTY = "secret";

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: java -Dsecret=<jwt.secret> GenToken <userId> [<userId> ...]");
            System.exit(1);
        }
        String secret = System.getProperty(SECRET_PROPERTY);
        if (secret == null || secret.trim().isEmpty()) {
            System.err.println("ERROR: -Dsecret=<jwt.secret> is required.");
            System.err.println("       Take it from jwt.secret in picture-backend/src/main/resources/application.yml");
            System.err.println("       (git-ignored). It is NOT stored in this file on purpose - see the class comment.");
            System.exit(2);
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            System.err.println("ERROR: the secret must be at least 32 bytes for HS256.");
            System.exit(2);
        }
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        for (String userId : args) {
            String token = Jwts.builder()
                    .setSubject(userId)
                    .setIssuedAt(new Date(now))
                    .setExpiration(new Date(now + 86400000L))
                    .signWith(key, SignatureAlgorithm.HS256)
                    .compact();
            System.out.println(userId + "=" + token);
        }
    }
}
