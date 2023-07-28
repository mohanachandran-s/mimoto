package io.mosip.mimoto.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.JsonUtils;
import io.mosip.mimoto.constant.ApiName;
import io.mosip.mimoto.core.http.ResponseWrapper;
import io.mosip.mimoto.dto.ErrorDTO;
import io.mosip.mimoto.dto.idp.TokenRequestDTO;
import io.mosip.mimoto.dto.idp.TokenResponseDTO;
import io.mosip.mimoto.dto.mimoto.*;
import io.mosip.mimoto.exception.ApisResourceAccessException;
import io.mosip.mimoto.exception.IdpException;
import io.mosip.mimoto.exception.PlatformErrorMessages;
import io.mosip.mimoto.service.RestClientService;
import io.mosip.mimoto.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
public class IdpController {

    private final Logger logger = LoggerUtil.getLogger(IdpController.class);
    private static final boolean useBearerToken = true;
    private static final String ID = "mosip.mimoto.idp";
    private Gson gson = new Gson();

    @Autowired
    private RestClientService<Object> restClientService;

    @Autowired
    private JoseUtil joseUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    RequestValidator requestValidator;

    @PostMapping("/binding-otp")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> otpRequest(@Valid @RequestBody BindingOtpRequestDto requestDTO, BindingResult result) throws Exception {
        logger.debug("Received binding-otp request : " + JsonUtils.javaObjectToJsonString(requestDTO));
        requestValidator.validateInputRequest(result);
        requestValidator.validateNotificationChannel(requestDTO.getRequest().getOtpChannels());
        ResponseWrapper<BindingOtpResponseDto> response = null;
        try {
            response = (ResponseWrapper<BindingOtpResponseDto>) restClientService
                    .postApi(ApiName.BINDING_OTP,
                            requestDTO, ResponseWrapper.class, useBearerToken);
            if (response == null)
                throw new IdpException();

        } catch (Exception e) {
            logger.error("Wallet binding otp error occured.", e);
            response = getErrorResponse(PlatformErrorMessages.MIMOTO_OTP_BINDING_EXCEPTION.getCode(), e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PostMapping(path = "/wallet-binding", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> request(@RequestBody WalletBindingRequestDTO requestDTO)
            throws Exception {

        logger.debug("Received wallet-binding request : " + JsonUtils.javaObjectToJsonString(requestDTO));

        ResponseWrapper<WalletBindingResponseDto> response = null;
        try {
            WalletBindingInnerRequestDto innerRequestDto = new WalletBindingInnerRequestDto();
            innerRequestDto.setChallengeList(requestDTO.getRequest().getChallengeList());
            innerRequestDto.setIndividualId(requestDTO.getRequest().getIndividualId());
            innerRequestDto.setPublicKey(JoseUtil.getJwkFromPublicKey(requestDTO.getRequest().getPublicKey()));
            innerRequestDto.setAuthFactorType(requestDTO.getRequest().getAuthFactorType());
            innerRequestDto.setFormat(requestDTO.getRequest().getFormat());

            WalletBindingInternalRequestDTO req = new WalletBindingInternalRequestDTO(requestDTO.getRequestTime(), innerRequestDto);

            ResponseWrapper<WalletBindingInternalResponseDto> internalResponse = (ResponseWrapper<WalletBindingInternalResponseDto>) restClientService
                    .postApi(ApiName.WALLET_BINDING,
                            req, ResponseWrapper.class, useBearerToken);

            if (internalResponse == null)
                throw new IdpException();

            response = joseUtil.addThumbprintAndKeyId(internalResponse);

        } catch (Exception e) {
            logger.error("Wallet binding error occured for tranaction id " + requestDTO.getRequest().getIndividualId(), e);
            response = getErrorResponse(PlatformErrorMessages.MIMOTO_WALLET_BINDING_EXCEPTION.getCode(), e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PostMapping(value = "/getToken", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    public ResponseEntity getAuthCode(@RequestParam Map<String, String> params) throws ApisResourceAccessException {

        logger.info("\n\n\n Started Token Call -> " );
        TokenRequestDTO tokenRequestDTO = new TokenRequestDTO();
        tokenRequestDTO.setCode(params.get("code"));
        tokenRequestDTO.setClient_id(params.get("client_id"));
        tokenRequestDTO.setGrant_type(params.get("grant_type"));
        tokenRequestDTO.setRedirect_uri(params.get("redirect_uri"));


        logger.info("Code -> " + tokenRequestDTO.getCode());
        logger.info("client_id -> " + tokenRequestDTO.getClient_id());
        logger.info("grant_type -> " + tokenRequestDTO.getGrant_type());
        logger.info("redirect_uri -> " + tokenRequestDTO.getRedirect_uri());

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity result = restTemplate.postForEntity("https://github.com/login/oauth/access_token", tokenRequestDTO, ResponseWrapper.class);

        logger.info("Result => " + result);
        logger.info("Result response => " + result.getBody());

        logger.info("\n\n\n Completed Token Call -> ");
        return ResponseEntity.status(HttpStatus.OK).body(result.getBody());

//        if("github".equals(issuer)){
//            RestTemplate restTemplate = new RestTemplate();
//            ResponseWrapper result = restTemplate.postForObject("https://github.com/login/oauth/access_token", tokenRequestDTO, ResponseWrapper.class);
//
//            logger.info("Result => " + result);
//            logger.info("Result response => " + result.getResponse());
//
//            logger.info("\n\n\n Completed Token Call -> ");
//            return ResponseEntity.status(HttpStatus.OK).body(result);
//        } else {
//            tokenRequestDTO.setClient_assertion_type("urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
//            tokenRequestDTO.setClient_assertion("");
//            ResponseWrapper<TokenResponseDTO> responseWrapper = (ResponseWrapper<TokenResponseDTO>) restClientService
//                    .postApi(ApiName.GET_TOKEN, tokenRequestDTO, ResponseWrapper.class, true);
//            return ResponseEntity.status(HttpStatus.OK).body(responseWrapper);
//        }
    }

    private ResponseWrapper getErrorResponse(String errorCode, String errorMessage) {

        List<ErrorDTO> errors = getErrors(errorCode, errorMessage);
        ResponseWrapper responseWrapper = new ResponseWrapper();
        responseWrapper.setResponse(null);
        responseWrapper.setResponsetime(DateUtils.getRequestTimeString());
        responseWrapper.setId(ID);
        responseWrapper.setErrors(errors);

        return responseWrapper;
    }

    private List<ErrorDTO> getErrors(String errorCode, String errorMessage) {
        ErrorDTO errorDTO = new ErrorDTO(errorCode, errorMessage);
        return Lists.newArrayList(errorDTO);
    }
}
