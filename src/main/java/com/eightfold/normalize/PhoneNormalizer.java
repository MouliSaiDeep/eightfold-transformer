package com.eightfold.normalize;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

public class PhoneNormalizer {
    private final PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();

    public String normalize(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return null;
        }
        try {
            // Default to US region if not specified or ambiguous
            PhoneNumber number = phoneUtil.parse(rawPhone, "US");
            if (phoneUtil.isValidNumber(number)) {
                return phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
            }
        } catch (Exception e) {
            // Ignore invalid phone formats gracefully
        }
        return null;
    }
}
