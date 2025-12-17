/**
 * JWT 토큰 생성기 (부하 테스트용)
 *
 * 사용법:
 *   node token-generator.js
 *   node token-generator.js --start 1000 --end 2000
 *   node token-generator.js --userId 1500
 */

const crypto = require('crypto');
const fs = require('fs');

// ⚠️ application.yml의 jwt.secret 값을 여기에 입력하세요
const JWT_SECRET = 'f279f3c64384508bd003b8a8b95362ea8c84128c688548d48402e6357c8461930fe5b8356637b41c55563e8a3a7cc376bbd6470346549f91fa740a7655908cb4';
const JWT_EXPIRATION = 604800000; // 7일 (ms) - 개발용 장기간

// Base64 URL 인코딩
function base64UrlEncode(str) {
  return Buffer.from(str)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '');
}

// JWT 토큰 생성
function generateJWT(userId) {
  // Header
  const header = {
    alg: 'HS512',
    typ: 'JWT'
  };

  // Payload - 서버의 JwtAuthenticationFilter 요구사항에 맞춤
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    sub: userId.toString(),  // subject = userId (String)
    kakaoId: 9000000000 + userId + 90,  // test data의 kakaoId (user_id + 90 offset)
    nickname: `테스트유저${String(userId + 90).padStart(4, '0')}`,  // test data의 nickname
    status: 'ACTIVE',  // 사용자 상태 (GUEST, ACTIVE, INACTIVE)
    type: 'access',  // 토큰 타입
    iat: now,
    exp: now + Math.floor(JWT_EXPIRATION / 1000)
  };

  // Encode header and payload
  const encodedHeader = base64UrlEncode(JSON.stringify(header));
  const encodedPayload = base64UrlEncode(JSON.stringify(payload));

  // Create signature
  const signatureInput = `${encodedHeader}.${encodedPayload}`;
  const signature = crypto
    .createHmac('sha512', JWT_SECRET)
    .update(signatureInput)
    .digest('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '');

  // Return complete JWT
  return `${encodedHeader}.${encodedPayload}.${signature}`;
}

// 명령줄 인수 파싱
function parseArgs() {
  const args = process.argv.slice(2);
  const config = {
    mode: 'bulk', // 'bulk' 또는 'single'
    startUserId: 1,
    endUserId: 1000,
    userId: null,
    outputFile: './tokens.json'
  };

  for (let i = 0; i < args.length; i++) {
    switch (args[i]) {
      case '--start':
        config.startUserId = parseInt(args[++i]);
        break;
      case '--end':
        config.endUserId = parseInt(args[++i]);
        break;
      case '--userId':
        config.mode = 'single';
        config.userId = parseInt(args[++i]);
        break;
      case '--output':
      case '-o':
        config.outputFile = args[++i];
        break;
      case '--help':
      case '-h':
        printHelp();
        process.exit(0);
    }
  }

  return config;
}

function printHelp() {
  console.log(`
╔═══════════════════════════════════════════════════════════╗
║           JWT 토큰 생성기 (부하 테스트용)                ║
╚═══════════════════════════════════════════════════════════╝

사용법:

  1. 대량 토큰 생성 (기본):
     node token-generator.js
     → 1-1000 사용자의 토큰을 tokens.json에 저장

  2. 범위 지정 대량 생성:
     node token-generator.js --start 1 --end 1000

  3. 단일 토큰 생성:
     node token-generator.js --userId 500

  4. 출력 파일 지정:
     node token-generator.js --output ./my-tokens.json

옵션:
  --start <id>      시작 사용자 ID (기본: 1)
  --end <id>        종료 사용자 ID (기본: 1000)
  --userId <id>     단일 사용자 ID
  --output, -o      출력 파일 경로 (기본: ./tokens.json)
  --help, -h        도움말 표시
  `);
}

// 메인 실행
function main() {
  const config = parseArgs();

  console.log('╔═══════════════════════════════════════════════════════════╗');
  console.log('║           JWT 토큰 생성기 시작                            ║');
  console.log('╠═══════════════════════════════════════════════════════════╣');

  if (config.mode === 'single') {
    // 단일 토큰 생성
    console.log(`║ 모드: 단일 토큰 생성                                      ║`);
    console.log(`║ 사용자 ID: ${String(config.userId).padEnd(45)}║`);
    console.log('╚═══════════════════════════════════════════════════════════╝');

    const token = generateJWT(config.userId);
    console.log('\n생성된 토큰:');
    console.log(token);

  } else {
    // 대량 토큰 생성
    const count = config.endUserId - config.startUserId + 1;
    console.log(`║ 모드: 대량 토큰 생성                                      ║`);
    console.log(`║ 범위: ${config.startUserId} ~ ${config.endUserId} (${count}개)`.padEnd(64) + '║');
    console.log(`║ 출력: ${config.outputFile.padEnd(49)}║`);
    console.log('╚═══════════════════════════════════════════════════════════╝');

    const tokens = {};
    const startTime = Date.now();

    for (let userId = config.startUserId; userId <= config.endUserId; userId++) {
      tokens[userId] = generateJWT(userId);

      // 진행 상황 표시 (100개마다)
      if ((userId - config.startUserId + 1) % 100 === 0) {
        const progress = Math.round(((userId - config.startUserId + 1) / count) * 100);
        process.stdout.write(`\r진행: ${progress}% (${userId - config.startUserId + 1}/${count})`);
      }
    }

    const elapsed = Date.now() - startTime;
    console.log(`\n\n✅ 토큰 생성 완료!`);
    console.log(`   - 생성 개수: ${count}개`);
    console.log(`   - 소요 시간: ${elapsed}ms`);
    console.log(`   - 평균 속도: ${Math.round(count / (elapsed / 1000))}개/초`);

    // JSON 파일로 저장
    fs.writeFileSync(config.outputFile, JSON.stringify(tokens, null, 2));
    console.log(`   - 저장 위치: ${config.outputFile}\n`);

    // 샘플 토큰 출력
    console.log('샘플 토큰:');
    console.log(`  User ${config.startUserId}: ${tokens[config.startUserId]}`);
  }
}

// 실행
if (require.main === module) {
  main();
}

module.exports = { generateJWT };
