package com.example.ratelimiter.presentation.interceptor;

import com.example.ratelimiter.application.service.RateLimiterService;
import com.example.ratelimiter.common.annotation.RateLimit;
import com.example.ratelimiter.common.exception.RateLimitExceededException;
import com.example.ratelimiter.domain.model.RateLimitResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Rate Limit AOP Aspect
 * 
 * SOLID 원칙:
 * - Single Responsibility: Rate limiting 검사만 담당
 * - Open/Closed: 새로운 식별자 추출 방법 추가 시 SpEL로 확장 가능
 * - Dependency Inversion: Service 인터페이스에 의존
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {
    
    private final RateLimiterService rateLimiterService;
    private final ExpressionParser parser = new SpelExpressionParser();
    
    @Around("@annotation(rateLimit)")
    public Object checkRateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        
        // 1. 식별자 추출
        String identifier = extractIdentifier(joinPoint, rateLimit.identifierExpression());
        
        // 2. Rate limit 검사
        RateLimitResult result = rateLimiterService.checkLimit(
                rateLimit.algorithm(),
                identifier
        );
        
        // 3. 결과 처리
        if (!result.isAllowed()) {
            log.warn("Rate limit exceeded - Algorithm: {}, Identifier: {}", 
                    rateLimit.algorithm(), identifier);
            throw new RateLimitExceededException(rateLimit.message(), result);
        }
        
        // 4. 원래 메서드 실행
        return joinPoint.proceed();
    }
    
    /**
     * SpEL 표현식을 사용하여 식별자 추출
     */
    private String extractIdentifier(ProceedingJoinPoint joinPoint, String expression) {
        try {
            EvaluationContext context = createEvaluationContext(joinPoint);
            Object value = parser.parseExpression(expression).getValue(context);
            return value != null ? value.toString() : "unknown";
            
        } catch (Exception e) {
            log.error("Failed to extract identifier from expression: {}", expression, e);
            return "unknown";
        }
    }
    
    /**
     * SpEL 평가 컨텍스트 생성
     */
    private EvaluationContext createEvaluationContext(ProceedingJoinPoint joinPoint) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        
        // HttpServletRequest를 컨텍스트에 추가
        ServletRequestAttributes attributes = 
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            context.setVariable("request", request);
        }
        
        // 메서드 파라미터를 컨텍스트에 추가
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        
        // paramNames와 args 길이가 다를 경우 ArrayIndexOutOfBoundsException 방지
        if (paramNames != null) {
            for (int i = 0; i < Math.min(paramNames.length, args.length); i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        
        return context;
    }
}
