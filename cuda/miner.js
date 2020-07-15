const https = require('https'),
      spawn = require('child_process').spawn,
      crypto = require('crypto');

async function cudaMiner(hash, difficulty) {
    return new Promise((resolve, reject) => {        
        let prc = spawn('./miner',  [ hash, difficulty ]),
            out = '',
            pre = '0'.repeat(Number(difficulty));

        prc.stdout.setEncoding('utf8');
        prc.stdout.on('data', (chunk) => { out += chunk.toString(); });        
        prc.on('close', (code) => {
            out = out.trim().split('.');
            
            if (code != 0) {
                return reject('Exit code non zero.');
            }

            if (hash != out[1]) {
                return reject('Target hash non equal to source.');
            }

            let sha256 = crypto.createHash('sha256').update(out.join('.')).digest('hex');

            if (sha256.indexOf(pre) != 0) {
                return reject('Target nonce non match.');
            }  
            
            resolve(Number(out[0]));
        });  
    })
}

async function getCommit() {
    return new Promise((resolve, reject) => {
        https.get("https://sdypp.craving.com.ar/api/miner/commit", (res) => {
            var data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => { 
                try {
                    data = JSON.parse(data);
                    resolve(data);
                } catch(err) {
                    reject(err);
                }
            });
        })
        .on('error', reject);
    })
}

async function postCommit(payload) {
    return new Promise((resolve, reject) => {
        payload = JSON.stringify(payload);
        
        let data = '';

        const req = https.request({
            hostname: 'sdypp.craving.com.ar',
            port: 443,
            path: '/api/miner/commit',
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': payload.length
            }
        }, (res) => {
            res.on('data', (chunk) => { data += chunk; }); 
            res.on('end', () => { resolve(data); }); 
        });

        req.on('error', reject);
        req.write(payload);
        req.end();
    });
}

async function sdyppMiner() {
    console.log('[ROUND] [START]');
    try {
        let source = await getCommit();
        if (source.hash) {
            console.log('[COMMIT] [' + source.hash + '] [' + source.challenge + ']');
            let nonce = await cudaMiner(source.hash, source.challenge);
            console.log('[NONCE] [' + nonce + ']');
            let response = await postCommit({ hash: source.hash, nonce: nonce });
        } else {
            console.log('[NOTHING TO-DO]');
        }
    } catch(err) {
        console.log('[ERROR]');
        console.error(err);
    }

    console.log('[ROUND] [END]');
    setTimeout(sdyppMiner, 60 * 1000);
}

sdyppMiner();