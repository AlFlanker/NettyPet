package dev.example.jettyclient.proxy.policies;

import java.util.List;
import org.eclipse.jetty.http.QuotedCSV;

/** Only the identity/wildcard rules needed by the optional response transformer. */
final class ContentCodingPreferences {
    private ContentCodingPreferences() { }

    static boolean identityAllowed(List<String> values) {
        boolean identitySeen = false;
        boolean identityDenied = false;
        boolean wildcardDenied = false;
        for (String item : new QuotedCSV(true, values.toArray(String[]::new))) {
            String[] parameters = item.split(";", -1);
            String coding = parameters[0].trim();
            boolean identity = coding.equalsIgnoreCase("identity");
            if (!identity && !coding.equals("*")) continue;
            boolean allowed = true;
            boolean qualitySeen = false;
            for (int i = 1; i < parameters.length; i++) {
                String parameter = parameters[i].trim();
                int equals = parameter.indexOf('=');
                if (equals < 0 || !parameter.substring(0, equals).trim().equalsIgnoreCase("q") || qualitySeen) {
                    allowed = false;
                    continue;
                }
                qualitySeen = true;
                allowed &= positiveQuality(parameter.substring(equals + 1).trim());
            }
            if (identity) {
                identitySeen = true;
                identityDenied |= !allowed;
            } else wildcardDenied |= !allowed;
        }
        return identitySeen ? !identityDenied : !wildcardDenied;
    }

    /** Valid qvalue, positive; malformed relevant preferences conservatively prohibit forcing identity. */
    private static boolean positiveQuality(String value) {
        if (value.isEmpty() || value.length() > 5 || (value.charAt(0) != '0' && value.charAt(0) != '1')) return false;
        boolean positive = value.charAt(0) == '1';
        if (value.length() == 1) return positive;
        if (value.charAt(1) != '.') return false;
        for (int i = 2; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9' || (value.charAt(0) == '1' && digit != '0')) return false;
            positive |= digit != '0';
        }
        return positive;
    }
}
