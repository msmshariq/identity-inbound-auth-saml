/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.authenticator.inbound.saml2sso.validator;

import org.apache.commons.lang.StringUtils;
import org.opensaml.common.SAMLVersion;
import org.opensaml.saml2.core.AuthnRequest;
import org.opensaml.saml2.core.Issuer;
import org.opensaml.saml2.core.NameID;
import org.opensaml.saml2.core.StatusCode;
import org.opensaml.saml2.core.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.bean.SAML2SSOContext;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.exception.InvalidSPEntityIdException;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.exception.SAML2SSORequestValidationException;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.exception.SAML2SSORuntimeException;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.exception.SAML2SSOServerException;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.model.Config;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.model.RequestValidatorConfig;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.request.SPInitRequest;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.util.AuthnReqSigUtil;
import org.wso2.carbon.identity.gateway.api.exception.GatewayClientException;
import org.wso2.carbon.identity.gateway.context.AuthenticationContext;
import org.wso2.carbon.identity.gateway.exception.InvalidServiceProviderIdException;
import org.wso2.carbon.identity.gateway.handler.GatewayHandlerResponse;

import java.util.List;

/**
 * SP Initiated SAML2 SSO Inbound Request Validator.
 */
public class SPInitValidator extends SAML2SSOValidator {

    private Logger logger = LoggerFactory.getLogger(SPInitValidator.class);

    @Override
    public boolean canHandle(org.wso2.carbon.identity.common.base.message.MessageContext messageContext) {
        if (messageContext instanceof AuthenticationContext) {
            AuthenticationContext authenticationContext = (AuthenticationContext) messageContext;
            if (authenticationContext.getInitialAuthenticationRequest() instanceof SPInitRequest) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getPriority(org.wso2.carbon.identity.common.base.message.MessageContext messageContext) {
        return 10;
    }

    protected SAML2SSOContext createInboundMessageContext(AuthenticationContext authenticationContext)
            throws SAML2SSORequestValidationException {

        SAML2SSOContext saml2SSOContext = super.createInboundMessageContext(authenticationContext);
        SPInitRequest spInitRequest = ((SPInitRequest) saml2SSOContext.getRequest());
        AuthnRequest authnRequest = spInitRequest.getAuthnRequest();
        Issuer issuer = authnRequest.getIssuer();
        if (issuer == null || (StringUtils.isBlank(issuer.getValue()) &&
                               StringUtils.isBlank(issuer.getSPProvidedID()))) {
            InvalidSPEntityIdException ex =
                    new InvalidSPEntityIdException(StatusCode.REQUESTER_URI, "Cannot find issuer.");
            ex.setInResponseTo(authnRequest.getID());
            ex.setAcsUrl(Config.getInstance().getErrorPageUrl());
            throw ex;
        }
        try {
            if (StringUtils.isNotBlank(issuer.getValue())) {
                authenticationContext.setServiceProviderId(issuer.getValue());
            } else if (StringUtils.isNotBlank(issuer.getSPProvidedID())) {
                authenticationContext.setServiceProviderId(issuer.getValue());
            }
        } catch (InvalidServiceProviderIdException e) {
            InvalidSPEntityIdException ex =
                    new InvalidSPEntityIdException(StatusCode.REQUESTER_URI, e.getMessage());
            ex.setInResponseTo(authnRequest.getID());
            ex.setAcsUrl(Config.getInstance().getErrorPageUrl());
            throw ex;
        } catch (GatewayClientException e) {
            SAML2SSORequestValidationException ex =
                    new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI, e.getMessage());
            ex.setInResponseTo(authnRequest.getID());
            ex.setAcsUrl(Config.getInstance().getErrorPageUrl());
            throw ex;
        }
        saml2SSOContext.setName(authenticationContext.getServiceProvider().getName());

        org.wso2.carbon.identity.gateway.common.model.sp.RequestValidatorConfig validatorConfig =
                getValidatorConfig(authenticationContext);
        RequestValidatorConfig requestValidatorConfig = new RequestValidatorConfig(validatorConfig);
        if (logger.isDebugEnabled()) {
            logger.debug(requestValidatorConfig.toString());
        }
        saml2SSOContext.setRequestValidatorConfig(requestValidatorConfig);
        return saml2SSOContext;
    }

    @Override
    public GatewayHandlerResponse validate(AuthenticationContext authenticationContext)
            throws SAML2SSORequestValidationException {

        SAML2SSOContext saml2SSOContext = createInboundMessageContext(authenticationContext);
        SPInitRequest spInitRequest = (SPInitRequest) saml2SSOContext.getRequest();
        AuthnRequest authnRequest = spInitRequest.getAuthnRequest();

        saml2SSOContext.setSPEntityId(authenticationContext.getServiceProviderId());
        saml2SSOContext.setId((authnRequest).getID());

        try {
            validateAuthnRequest(authnRequest, saml2SSOContext);
        } catch (SAML2SSOServerException e) {
            // TODO: Throw GatewayServerException from validation handler.
            SAML2SSORuntimeException ex = new SAML2SSORuntimeException(StatusCode.RESPONDER_URI, e.getMessage(), e);
            ex.setInResponseTo(e.getInResponseTo());
            ex.setAcsUrl(e.getAcsUrl());
            throw ex;
        }

        return new GatewayHandlerResponse();

    }

    protected void validateAuthnRequest(AuthnRequest authnReq, SAML2SSOContext saml2SSOContext)
            throws SAML2SSORequestValidationException, SAML2SSOServerException {

        String appName = saml2SSOContext.getName();

        RequestValidatorConfig requestValidatorConfig = saml2SSOContext.getRequestValidatorConfig();
        validateACS(authnReq.getAssertionConsumerServiceURL(), saml2SSOContext.getId(), saml2SSOContext,
                    requestValidatorConfig);

        if (!(SAMLVersion.VERSION_20.equals(authnReq.getVersion()))) {
            SAML2SSORequestValidationException ex =
                    new SAML2SSORequestValidationException(StatusCode.VERSION_MISMATCH_URI,
                                                           "Invalid SAML Version in AuthnRequest. SAML Version should" +
                                                           " be equal to 2.0.");
            ex.setInResponseTo(saml2SSOContext.getId());
            ex.setAcsUrl(saml2SSOContext.getAssertionConsumerURL());
            throw ex;
        }

        Issuer issuer = authnReq.getIssuer();

        if (StringUtils.isNotBlank(issuer.getFormat()) && !NameID.ENTITY.equals(issuer.getFormat())) {
            SAML2SSORequestValidationException ex =
                    new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI,
                                                           "Invalid Issuer Format attribute value " + issuer
                                                                   .getFormat());
            ex.setInResponseTo(saml2SSOContext.getId());
            ex.setAcsUrl(saml2SSOContext.getAssertionConsumerURL());
            throw ex;
        }

        saml2SSOContext.setForce(authnReq.isForceAuthn());
        saml2SSOContext.setPassive(authnReq.isPassive());

        // TODO: Validate the NameID Format
        Subject subject = authnReq.getSubject();
        if (subject != null && subject.getNameID() != null &&
            StringUtils.isNotBlank(subject.getNameID().getValue())) {
            saml2SSOContext.setSubject(subject.getNameID().getValue());
        }

        // subject confirmation should not exist
        if (subject != null && subject.getSubjectConfirmations() != null &&
            !subject.getSubjectConfirmations().isEmpty()) {
            String message = "Invalid Request message. A Subject confirmation method " +
                             "found " + subject.getSubjectConfirmations().get(0);
            SAML2SSORequestValidationException ex =
                    new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI, message);
            ex.setInResponseTo(saml2SSOContext.getId());
            ex.setAcsUrl(saml2SSOContext.getAssertionConsumerURL());
            throw ex;
        }

        Integer index = authnReq.getAttributeConsumingServiceIndex();
        //according the spec, should be an unsigned short
        if (index != null && !(index < 1)) {
            saml2SSOContext.setAttributeConsumingServiceIndex(index);
        }

        // Validate the assertion consumer url, only if request is not signed.
        if (requestValidatorConfig.isRequireSignatureValidation()) {

            List<String> idpUrlSet = Config.getInstance().getDestinationUrls();

            if (authnReq.getDestination() == null || !idpUrlSet.contains(authnReq.getDestination())) {
                String msg = "Destination validation for AuthnRequest failed. " + "Received: [" +
                             saml2SSOContext.getDestination() + "]." + " Expected one in the list: [" + StringUtils
                                     .join(idpUrlSet, ',') + "]";
                SAML2SSORequestValidationException ex =
                        new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI, msg);
                ex.setInResponseTo(saml2SSOContext.getId());
                ex.setAcsUrl(saml2SSOContext.getAssertionConsumerURL());
                throw ex;
            }
            saml2SSOContext.setDestination(authnReq.getDestination());

            boolean isSignatureValid = AuthnReqSigUtil.validateAuthnRequestSignature(authnReq,
                                                                                     saml2SSOContext,
                                                                                     requestValidatorConfig);

            if (!isSignatureValid) {
                SAML2SSORequestValidationException ex =
                        new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI,
                                                               "Signature validation for AuthnRequest failed.");
                ex.setInResponseTo(saml2SSOContext.getId());
                ex.setAcsUrl(saml2SSOContext.getAssertionConsumerURL());
                throw ex;
            }

        } else {

            String acsUrl = saml2SSOContext.getAssertionConsumerURL();
            if (StringUtils.isBlank(acsUrl) || !requestValidatorConfig.getAssertionConsumerUrlList()
                    .contains(acsUrl)) {
                String message = "Invalid Assertion Consumer URL value '" + acsUrl + "' in the AuthnRequest " +
                                 "message from '" + appName;
                SAML2SSORequestValidationException ex =
                        new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI, message);
                ex.setInResponseTo(saml2SSOContext.getId());
                ex.setAcsUrl(saml2SSOContext.getAssertionConsumerURL());
                throw ex;
            }
        }
    }

    protected void validateACS(String requestedACSUrl, String inResponseTo,
                               SAML2SSOContext saml2SSOContext, RequestValidatorConfig requestValidatorConfig)
            throws SAML2SSORequestValidationException {

        if (!requestValidatorConfig.getAssertionConsumerUrlList().contains(requestedACSUrl)) {
            SAML2SSORequestValidationException ex =
                    new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI,
                                                           "Invalid Assertion Consumer Service URL in the " +
                                                           "AuthnRequest message.");
            ex.setInResponseTo(inResponseTo);
            ex.setAcsUrl(Config.getInstance().getErrorPageUrl());
            throw ex;
        }
        saml2SSOContext.setAssertionConsumerUrl(requestedACSUrl);
    }
}
