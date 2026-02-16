package com.example.ratelimiter.infrastructure.redis;

import java.util.List;

/**
 * Redis Lua Script 실행을 추상화한 인터페이스
 * 
 * SOLID 원칙:
 * - Dependency Inversion: Redis 구현 세부사항을 숨김
 * - Single Responsibility: Lua 스크립트 실행만 담당
 */
public interface RedisScriptExecutor {
    
    /**
     * Lua 스크립트를 실행하고 결과 반환
     * 
     * @param script Lua 스크립트
     * @param keys Redis 키 목록
     * @param args 스크립트 인자 목록
     * @return 실행 결과
     */
    List<Long> executeLuaScript(String script, List<String> keys, List<String> args);
    
    /**
     * 키 삭제
     * 
     * @param keys 삭제할 키 목록
     */
    void deleteKeys(String... keys);
    
    /**
     * 패턴으로 키 검색
     * 
     * @param pattern 검색 패턴
     * @return 매칭되는 키 목록
     */
    List<String> findKeys(String pattern);
}
