#!/usr/bin/env python3
"""
🚀 1천만건 알림 데이터 생성기
Python으로 MySQL에 직접 대용량 데이터 삽입
"""

import mysql.connector
import time
from datetime import datetime, timedelta
import random

def create_10million_notifications():
    print("🔥🔥🔥 1천만건 알림 생성 시작 🔥🔥🔥")
    
    # MySQL 연결
    config = {
        'host': '172.16.24.224',
        'user': 'onlyone',
        'password': 'password',
        'database': 'buddkit',
        'autocommit': False
    }
    
    try:
        connection = mysql.connector.connect(**config)
        cursor = connection.cursor()
        
        # 알림 타입 ID 가져오기
        cursor.execute("SELECT type_id FROM notification_type WHERE type = 'CHAT' LIMIT 1")
        notification_type_id = cursor.fetchone()[0]
        print(f"📋 알림 타입 ID: {notification_type_id}")
        
        # 현재 시간
        start_time = time.time()
        now = datetime.now()
        
        # 배치 설정
        batch_size = 50000  # 5만개씩 배치
        total_batches = 200  # 총 200번 = 1000만개
        
        print(f"📊 {batch_size:,}개씩 {total_batches}배치로 처리")
        
        # 배치별로 처리
        for batch_num in range(total_batches):
            batch_start = time.time()
            
            # 배치 데이터 생성
            values = []
            for i in range(batch_size):
                record_id = batch_num * batch_size + i
                user_id = 100001 + (record_id % 500)  # 500명 사용자 순환
                content = f"🚀PYTHON알림#{record_id + 1}👤{user_id}"
                is_read = 1 if (record_id % 10 < 3) else 0  # 30% 읽음
                sse_sent = 1 if (record_id % 10 < 8) else 0  # 80% SSE 성공
                fcm_sent = 1 if (record_id % 10 < 7) else 0  # 70% FCM 성공
                created_at = now - timedelta(seconds=(record_id % 86400))  # 24시간 내 랜덤
                
                values.append((
                    user_id, notification_type_id, content, 
                    is_read, sse_sent, fcm_sent, 
                    created_at, now
                ))
            
            # 배치 INSERT 실행
            insert_sql = """
            INSERT INTO notification 
            (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            """
            
            cursor.executemany(insert_sql, values)
            connection.commit()
            
            # 진행률 출력
            batch_time = time.time() - batch_start
            total_elapsed = time.time() - start_time
            progress = ((batch_num + 1) / total_batches) * 100
            records_inserted = (batch_num + 1) * batch_size
            
            if (batch_num + 1) % 10 == 0:  # 10배치마다 출력
                avg_batch_time = total_elapsed / (batch_num + 1)
                estimated_remaining = avg_batch_time * (total_batches - batch_num - 1)
                
                print(f"🚀 진행률: {progress:.1f}% "
                      f"({records_inserted:,} / 10,000,000) | "
                      f"⏱️ 배치시간: {batch_time:.1f}초 | "
                      f"🎯 예상완료: {estimated_remaining/60:.1f}분")
        
        # 최종 결과
        total_time = time.time() - start_time
        cursor.execute("SELECT COUNT(*) FROM notification WHERE content LIKE '🚀PYTHON알림#%'")
        final_count = cursor.fetchone()[0]
        
        print("🎉🎉🎉 1천만건 생성 완료!!! 🎉🎉🎉")
        print(f"⏰ 총 소요시간: {total_time/60:.1f}분")
        print(f"📊 생성된 레코드: {final_count:,}개")
        print(f"⚡ 평균 처리속도: {final_count/total_time:.0f}개/초")
        
        # 통계 조회
        print("\n🏆 === 생성 통계 === 🏆")
        
        cursor.execute("""
        SELECT 
            CASE WHEN is_read = 1 THEN '✅읽음' ELSE '📮안읽음' END as status,
            COUNT(*) as count,
            ROUND(COUNT(*) * 100.0 / %s, 1) as percentage
        FROM notification 
        WHERE content LIKE '🚀PYTHON알림#%%'
        GROUP BY is_read
        """, (final_count,))
        
        for row in cursor.fetchall():
            print(f"📖 {row[0]}: {row[1]:,}개 ({row[2]}%)")
        
        print("\n🎊 Python으로 1천만건 대용량 데이터 생성 완료! 🎊")
        
    except mysql.connector.Error as e:
        print(f"❌ 오류 발생: {e}")
        
    finally:
        if 'connection' in locals():
            cursor.close()
            connection.close()

if __name__ == "__main__":
    create_10million_notifications()