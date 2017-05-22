/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2017 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.ldap;

import com.novell.ldapchai.ChaiFactory;
import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.exception.ChaiOperationException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import com.novell.ldapchai.provider.ChaiProvider;
import password.pwm.PwmApplication;
import password.pwm.bean.PasswordStatus;
import password.pwm.bean.ResponseInfoBean;
import password.pwm.bean.SessionLabel;
import password.pwm.bean.UserIdentity;
import password.pwm.config.Configuration;
import password.pwm.config.FormConfiguration;
import password.pwm.config.FormUtility;
import password.pwm.config.PwmSetting;
import password.pwm.config.UserPermission;
import password.pwm.config.option.ForceSetupPolicy;
import password.pwm.config.profile.ChallengeProfile;
import password.pwm.config.profile.LdapProfile;
import password.pwm.config.profile.ProfileType;
import password.pwm.config.profile.ProfileUtility;
import password.pwm.config.profile.PwmPasswordPolicy;
import password.pwm.config.profile.PwmPasswordRule;
import password.pwm.config.profile.UpdateAttributesProfile;
import password.pwm.error.PwmDataValidationException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.svc.PwmService;
import password.pwm.util.PasswordData;
import password.pwm.util.PwmPasswordRuleValidator;
import password.pwm.util.java.CachingProxyWrapper;
import password.pwm.util.java.JavaHelper;
import password.pwm.util.java.TimeDuration;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.operations.CrService;
import password.pwm.util.operations.OtpService;
import password.pwm.util.operations.PasswordUtility;
import password.pwm.util.operations.otp.OTPUserRecord;

import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class UserInfoReader implements UserInfo {
    private static final PwmLogger LOGGER = PwmLogger.forClass(UserInfoReader.class);

    private final UserIdentity userIdentity;
    private final PasswordData currentPassword;
    private final Locale locale;

    private final ChaiUser chaiUser;
    private final SessionLabel sessionLabel;
    private final PwmApplication pwmApplication;

    /** A reference to this object, but with memorized (cached) method implementations.  In most cases references to 'this'
       inside this class should use this {@code selfCachedReference} instead.
     */
    private UserInfo selfCachedReference;

    private UserInfoReader(
            final UserIdentity userIdentity,
            final PasswordData currentPassword,
            final SessionLabel sessionLabel,
            final Locale locale,
            final PwmApplication pwmApplication,
            final ChaiProvider chaiProvider
    )
            throws ChaiUnavailableException
    {
        this.userIdentity = userIdentity;
        this.currentPassword = currentPassword;
        this.pwmApplication = pwmApplication;
        this.locale = locale;
        this.sessionLabel = sessionLabel;

        final ChaiProvider cachingProvider = CachingProxyWrapper.create(ChaiProvider.class, chaiProvider);
        this.chaiUser = ChaiFactory.createChaiUser(userIdentity.getUserDN(), cachingProvider);
    }

    static UserInfo createLazyUserInfo(
            final UserIdentity userIdentity,
            final PasswordData currentPassword,
            final SessionLabel sessionLabel,
            final Locale locale,
            final PwmApplication pwmApplication,
            final ChaiProvider chaiProvider
    )
            throws ChaiUnavailableException
    {
        final UserInfoReader userInfo = new UserInfoReader(userIdentity, currentPassword, sessionLabel, locale, pwmApplication, chaiProvider);
        final UserInfo selfCachedReference = CachingProxyWrapper.create(UserInfo.class, userInfo);
        userInfo.selfCachedReference = selfCachedReference;
        return selfCachedReference;
    }

    @Override
    public Map<String, String> getCachedPasswordRuleAttributes() throws PwmUnrecoverableException
    {
        try {
            final Set<String> interestingUserAttributes = UserInfoFactory.figurePasswordRuleAttributes(selfCachedReference);
            final Map<String, String> allUserAttrs = chaiUser.readStringAttributes(interestingUserAttributes);
            return Collections.unmodifiableMap(allUserAttrs);
        } catch (ChaiOperationException e) {
            LOGGER.warn(sessionLabel, "error retrieving user cached password rule attributes " + e);
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getCachedAttributeValues() throws PwmUnrecoverableException
    {
        final LdapProfile ldapProfile = getUserIdentity().getLdapProfile(pwmApplication.getConfig());
        final List<String> cachedAttributeNames = ldapProfile.readSettingAsStringArray(PwmSetting.CACHED_USER_ATTRIBUTES);
        if (cachedAttributeNames != null && !cachedAttributeNames.isEmpty()) {
            try {
                final Map<String, String> attributeValues = chaiUser.readStringAttributes(new HashSet<>(cachedAttributeNames));
                return Collections.unmodifiableMap(attributeValues);
            } catch (ChaiOperationException e) {
                LOGGER.warn(sessionLabel, "error retrieving user cache attributes: " + e);
            } catch (ChaiUnavailableException e) {
                throw PwmUnrecoverableException.fromChaiException(e);
            }
        }
        return Collections.emptyMap();
    }

    @Override
    public Instant getLastLdapLoginTime() throws PwmUnrecoverableException
    {
        try {
            final Date lastLoginTime = chaiUser.readLastLoginTime();
            return lastLoginTime == null
                    ? null
                    : lastLoginTime.toInstant();
        } catch (ChaiOperationException e) {
            LOGGER.warn(sessionLabel, "error reading user's last ldap login time: " + e.getMessage());
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
        return null;
    }

    @Override
    public ChallengeProfile getChallengeProfile() throws PwmUnrecoverableException
    {
        final PwmPasswordPolicy pwmPasswordPolicy = selfCachedReference.getPasswordPolicy();
        final CrService crService = pwmApplication.getCrService();
        return crService.readUserChallengeProfile(
                sessionLabel,
                getUserIdentity(),
                chaiUser,
                pwmPasswordPolicy,
                locale
        );
    }

    @Override
    public PwmPasswordPolicy getPasswordPolicy() throws PwmUnrecoverableException
    {
        return PasswordUtility.readPasswordPolicyForUser(pwmApplication, sessionLabel, getUserIdentity(), chaiUser, locale);
    }

    @Override
    public UserIdentity getUserIdentity()
    {
        return userIdentity;
    }

    @Override
    public Instant getPasswordExpirationTime() throws PwmUnrecoverableException
    {
        return LdapOperationsHelper.readPasswordExpirationTime(chaiUser);
    }

    @Override
    public String getUsername() throws PwmUnrecoverableException
    {
        final LdapProfile ldapProfile = getUserIdentity().getLdapProfile(pwmApplication.getConfig());
        final String uIDattr = ldapProfile.getUsernameAttribute();
        try {
            return chaiUser.readStringAttribute(uIDattr);
        } catch (ChaiOperationException e) {
            LOGGER.error(sessionLabel, "error reading userID attribute: " + e.getMessage());
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
        return null;
    }

    @Override
    public PasswordStatus getPasswordState() throws PwmUnrecoverableException
    {
        final Configuration config = pwmApplication.getConfig();
        final PasswordStatus.PasswordStatusBuilder passwordStatusBuilder = PasswordStatus.builder();
        final String userDN = chaiUser.getEntryDN();
        final PwmPasswordPolicy passwordPolicy = selfCachedReference.getPasswordPolicy();

        final long startTime = System.currentTimeMillis();
        LOGGER.trace(sessionLabel, "beginning password status check process for " + userDN);

        // check if password meets existing policy.
        if (passwordPolicy.getRuleHelper().readBooleanValue(PwmPasswordRule.EnforceAtLogin)) {
            if (currentPassword != null) {
                try {
                    final PwmPasswordRuleValidator passwordRuleValidator = new PwmPasswordRuleValidator(pwmApplication, passwordPolicy);
                    passwordRuleValidator.testPassword(currentPassword, null, selfCachedReference, chaiUser);
                } catch (PwmDataValidationException | PwmUnrecoverableException e) {
                    LOGGER.debug(sessionLabel, "user " + userDN + " password does not conform to current password policy (" + e.getMessage() + "), marking as requiring change.");
                    passwordStatusBuilder.violatesPolicy(true);
                } catch (ChaiUnavailableException e) {
                    throw PwmUnrecoverableException.fromChaiException(e);
                }
            }
        }

        boolean ldapPasswordExpired = false;
        try {
            ldapPasswordExpired = chaiUser.isPasswordExpired();

            if (ldapPasswordExpired) {
                LOGGER.trace(sessionLabel, "password for " + userDN + " appears to be expired");
            } else {
                LOGGER.trace(sessionLabel, "password for " + userDN + " does not appear to be expired");
            }
        } catch (ChaiOperationException e) {
            LOGGER.info(sessionLabel, "error reading LDAP attributes for " + userDN + " while reading isPasswordExpired(): " + e.getMessage());
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }

        final Instant ldapPasswordExpirationTime = selfCachedReference.getPasswordExpirationTime();

        boolean preExpired = false;
        if (ldapPasswordExpirationTime != null) {
            final TimeDuration expirationInterval = TimeDuration.fromCurrent(ldapPasswordExpirationTime);
            LOGGER.trace(sessionLabel, "read password expiration time: "
                    + JavaHelper.toIsoDate(ldapPasswordExpirationTime)
                    + ", " + expirationInterval.asCompactString() + " from now"
            );
            final TimeDuration diff = TimeDuration.fromCurrent(ldapPasswordExpirationTime);

            // now check to see if the user's expire time is within the 'preExpireTime' setting.
            final long preExpireMs = config.readSettingAsLong(PwmSetting.PASSWORD_EXPIRE_PRE_TIME) * 1000;
            if (diff.getTotalMilliseconds() > 0 && diff.getTotalMilliseconds() < preExpireMs) {
                LOGGER.debug(sessionLabel, "user " + userDN + " password will expire within "
                        + diff.asCompactString()
                        + ", marking as pre-expired");
                preExpired = true;
            } else if (ldapPasswordExpired) {
                preExpired = true;
                LOGGER.debug(sessionLabel, "user " + userDN + " password is expired, marking as pre-expired.");
            }

            // now check to see if the user's expire time is within the 'preWarnTime' setting.
            final long preWarnMs = config.readSettingAsLong(PwmSetting.PASSWORD_EXPIRE_WARN_TIME) * 1000;
            // don't check if the 'preWarnTime' setting is zero or less than the expirePreTime
            if (!ldapPasswordExpired && !preExpired) {
                if (!(preWarnMs == 0 || preWarnMs < preExpireMs)) {
                    if (diff.getTotalMilliseconds() > 0 && diff.getTotalMilliseconds() < preWarnMs) {
                        LOGGER.debug(sessionLabel,
                                "user " + userDN + " password will expire within "
                                        + diff.asCompactString()
                                        + ", marking as within warn period");
                        passwordStatusBuilder.warnPeriod(true);
                    } else if (ldapPasswordExpired) {
                        LOGGER.debug(sessionLabel,
                                "user " + userDN + " password is expired, marking as within warn period");
                        passwordStatusBuilder.warnPeriod(true);
                    }
                }
            }

            passwordStatusBuilder.preExpired(preExpired);
        }

        LOGGER.debug(sessionLabel, "completed user password status check for " + userDN + " " + passwordStatusBuilder + " (" + TimeDuration.fromCurrent(startTime).asCompactString() + ")");
        passwordStatusBuilder.expired(ldapPasswordExpired);
        return passwordStatusBuilder.build();
    }

    @Override
    public boolean isRequiresNewPassword() throws PwmUnrecoverableException
    {
        final PasswordStatus passwordStatus = selfCachedReference.getPasswordState();
        final List<UserPermission> updateProfilePermission = pwmApplication.getConfig().readSettingAsUserPermission(
                PwmSetting.QUERY_MATCH_CHANGE_PASSWORD);
        if (!LdapPermissionTester.testUserPermissions(pwmApplication, sessionLabel, userIdentity, updateProfilePermission)) {
            LOGGER.debug(sessionLabel,
                    "checkPassword: " + userIdentity.toString() + " user does not have permission to change password");
            return false;
        }

        if (passwordStatus.isExpired()) {
            LOGGER.debug(sessionLabel, "checkPassword: password is expired, marking new password as required");
            return true;
        }

        if (passwordStatus.isPreExpired()) {
            LOGGER.debug(sessionLabel, "checkPassword: password is pre-expired, marking new password as required");
            return true;
        }

        if (passwordStatus.isWarnPeriod()) {
            LOGGER.debug(sessionLabel, "checkPassword: password is within warn period, marking new password as required");
            return true;
        }

        if (passwordStatus.isViolatesPolicy()) {
            LOGGER.debug(sessionLabel, "checkPassword: current password violates password policy, marking new password as required");
            return true;
        }

        return false;
    }

    @Override
    public boolean isRequiresResponseConfig() throws PwmUnrecoverableException
    {
        final CrService crService = pwmApplication.getCrService();
        try {
            return crService.checkIfResponseConfigNeeded(
                    pwmApplication,
                    sessionLabel,
                    getUserIdentity(),
                    selfCachedReference.getChallengeProfile().getChallengeSet(),
                    selfCachedReference.getResponseInfoBean());
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
    }

    @Override
    public boolean isRequiresOtpConfig() throws PwmUnrecoverableException
    {
        LOGGER.trace(sessionLabel, "checkOtp: beginning process to check if user OTP setup is required");

        final UserIdentity userIdentity = getUserIdentity();

        if (!pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.OTP_ENABLED)) {
            LOGGER.trace(sessionLabel, "checkOtp: OTP is not enabled, user OTP setup is not required");
            return false;
        }

        final OTPUserRecord otpUserRecord = selfCachedReference.getOtpUserRecord();
        final boolean hasStoredOtp = otpUserRecord != null && otpUserRecord.getSecret() != null;

        if (hasStoredOtp) {
            LOGGER.trace(sessionLabel, "checkOtp: user has existing valid otp record, user OTP setup is not required");
            return false;
        }

        final List<UserPermission> setupOtpPermission = pwmApplication.getConfig().readSettingAsUserPermission(PwmSetting.OTP_SETUP_USER_PERMISSION);
        if (!LdapPermissionTester.testUserPermissions(pwmApplication, sessionLabel, userIdentity, setupOtpPermission)) {
            LOGGER.trace(sessionLabel, "checkOtp: " + userIdentity.toString() + " is not eligible for checkOtp due to query match");
            return false;
        }

        final ForceSetupPolicy policy = pwmApplication.getConfig().readSettingAsEnum(PwmSetting.OTP_FORCE_SETUP, ForceSetupPolicy.class);

        // hasStoredOtp is always true at this point, so if forced then update needed
        LOGGER.debug(sessionLabel, "checkOtp: user does not have existing valid otp record, user OTP setup is required");
        return policy == ForceSetupPolicy.FORCE || policy == ForceSetupPolicy.FORCE_ALLOW_SKIP;
    }

    @Override
    public boolean isRequiresUpdateProfile() throws PwmUnrecoverableException
    {
        final Configuration configuration = pwmApplication.getConfig();

        if (!pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.UPDATE_PROFILE_ENABLE)) {
            LOGGER.debug(sessionLabel, "checkProfiles: " + userIdentity.toString() + " profile module is not enabled");
            return false;
        }

        UpdateAttributesProfile updateAttributesProfile = null;
        final Map<ProfileType, String> profileIDs = selfCachedReference.getProfileIDs();
        if (profileIDs.containsKey(ProfileType.UpdateAttributes)) {
            updateAttributesProfile = configuration.getUpdateAttributesProfile().get(profileIDs.get(ProfileType.UpdateAttributes));
        }

        if (updateAttributesProfile == null) {
            return false;
        }

        if (!updateAttributesProfile.readSettingAsBoolean(PwmSetting.UPDATE_PROFILE_FORCE_SETUP)) {
            LOGGER.debug(sessionLabel, "checkProfiles: " + userIdentity.toString() + " profile force setup is not enabled");
            return false;
        }

        final List<FormConfiguration> updateFormFields = updateAttributesProfile.readSettingAsForm(PwmSetting.UPDATE_PROFILE_FORM);

        // populate the map from ldap

        try {
            final UserDataReader userDataReader = new LdapUserDataReader(userIdentity, chaiUser);
            final Map<FormConfiguration, List<String>> valueMap = FormUtility.populateFormMapFromLdap(updateFormFields, sessionLabel, userDataReader, FormUtility.Flag.ReturnEmptyValues);
            final Map<FormConfiguration, String> singleValueMap = FormUtility.multiValueMapToSingleValue(valueMap);
            FormUtility.validateFormValues(configuration, singleValueMap, locale);
            LOGGER.debug(sessionLabel, "checkProfile: " + userIdentity + " has value for attributes, update profile will not be required");
            return false;
        } catch (PwmDataValidationException e) {
            LOGGER.debug(sessionLabel, "checkProfile: " + userIdentity + " does not have good attributes (" + e.getMessage() + "), update profile will be required");
            return true;
        } catch (PwmUnrecoverableException e) {
            e.printStackTrace();
        }

        return false;
    }

    @Override
    public Instant getPasswordLastModifiedTime() throws PwmUnrecoverableException
    {
        try {
            return PasswordUtility.determinePwdLastModified(pwmApplication, sessionLabel, userIdentity);
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
    }

    @Override
    public String getUserEmailAddress() throws PwmUnrecoverableException
    {
        final LdapProfile ldapProfile = getUserIdentity().getLdapProfile(pwmApplication.getConfig());
        final String ldapEmailAttribute = ldapProfile.readSettingAsString(PwmSetting.EMAIL_USER_MAIL_ATTRIBUTE);
        try {
            return chaiUser.readStringAttribute(ldapEmailAttribute);
        } catch (ChaiOperationException e) {
            LOGGER.error(sessionLabel, "error reading email address attribute: " + e.getMessage());
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
        return null;
    }

    @Override
    public String getUserSmsNumber() throws PwmUnrecoverableException
    {
        final LdapProfile ldapProfile = getUserIdentity().getLdapProfile(pwmApplication.getConfig());
        final String ldapSmsAttribute = ldapProfile.readSettingAsString(PwmSetting.SMS_USER_PHONE_ATTRIBUTE);
        try {
            return chaiUser.readStringAttribute(ldapSmsAttribute);
        } catch (ChaiOperationException e) {
            LOGGER.error(sessionLabel, "error reading sms number attribute: " + e.getMessage());
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
        return null;
    }

    @Override
    public String getUserGuid() throws PwmUnrecoverableException
    {
        try {
            return LdapOperationsHelper.readLdapGuidValue(pwmApplication, sessionLabel, userIdentity, false);
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
    }

    @Override
    public ResponseInfoBean getResponseInfoBean() throws PwmUnrecoverableException
    {
        final CrService crService = pwmApplication.getCrService();
        try {
            return crService.readUserResponseInfo(sessionLabel, getUserIdentity(), chaiUser);
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
    }

    @Override
    public OTPUserRecord getOtpUserRecord() throws PwmUnrecoverableException
    {
        final OtpService otpService = pwmApplication.getOtpService();
        if (otpService != null && otpService.status() == PwmService.STATUS.OPEN) {
            try {
                return otpService.readOTPUserConfiguration(sessionLabel, userIdentity);
            } catch (ChaiUnavailableException e) {
                throw PwmUnrecoverableException.fromChaiException(e);
            }
        }
        return null;
    }

    @Override
    public Instant getAccountExpirationTime() throws PwmUnrecoverableException
    {
        try {
            final Date accountExpireDate = chaiUser.readAccountExpirationDate();
            return accountExpireDate == null
                    ? null
                    : accountExpireDate.toInstant();
        } catch (ChaiOperationException e) {
            LOGGER.warn(sessionLabel, "error reading user's account expiration time: " + e.getMessage());
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
        return null;
    }

    @Override
    public Map<ProfileType, String> getProfileIDs() throws PwmUnrecoverableException
    {
        final Map<ProfileType, String> returnMap = new HashMap<>();
        for (final ProfileType profileType : ProfileType.values()) {
            if (profileType.isAuthenticated()) {
                final String profileID = ProfileUtility.discoverProfileIDforUser(pwmApplication, sessionLabel, userIdentity, profileType);
                returnMap.put(profileType, profileID);
                if (profileID != null) {
                    LOGGER.debug(sessionLabel, "assigned " + profileType.toString() + " profileID \"" + profileID + "\" to " + userIdentity.toDisplayString());
                } else {
                    LOGGER.debug(sessionLabel, profileType.toString() + " has no matching profiles for user " + userIdentity.toDisplayString());
                }
            }
        }
        return Collections.unmodifiableMap(returnMap);
    }
}