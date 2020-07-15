// -------------------------------------------------------------------------
	
function interpolate(template, data, prefix, history) {
	prefix || (prefix = '');
	history || (history = []);

	history.push(data);

	for (var name in data) {
		interpolate.anyProp.lastIndex = 0;
		interpolate.anyGroup.lastIndex = 0;
		if (!interpolate.anyProp.test(template) && !interpolate.anyGroup.test(template)) {
			break;
		}

		if (typeof data[name] !== "object") {
			continue;
		}

		if (Array.isArray(data[name])) {
			template = template.replace(new RegExp('\\[' + name + '\\]([\\s\\S]*?)\\[\\/' + name + '\\]', 'gi'), function(match, template) {
				return data[name].map(function(data, staticIdx) {
					if (history.indexOf(data[name]) === -1) {
						return interpolate(template, data, prefix, history).replace(new RegExp('\\{:idx:\\}', 'gi'), staticIdx);
					}

					return template.replace(new RegExp('\\{:idx:\\}', 'gi'), staticIdx);
				})
				.join("");
			});

			continue;
		}
	}

	for (var name in data) {
		interpolate.anyProp.lastIndex = 0;
		interpolate.anyGroup.lastIndex = 0;
		if (!interpolate.anyProp.test(template) && !interpolate.anyGroup.test(template)) {
			break;
		}

		if (typeof data[name] === "object") {
			// Prevent circular references.
			if (history.indexOf(data[name]) === -1) {
				template = interpolate(template, data[name], (prefix ? prefix : '') + name + '\.', history);
			}

			continue;
		}

		template = template.replace(new RegExp('\\{' + prefix + name + '\\}', 'gi'), data[name]);
	}

	if (prefix === '') {
		interpolate.anyProp.lastIndex = 0;
		template = template.replace(interpolate.anyProp, '');
	}

	return template;
}

interpolate.anyProp  = new RegExp('\\{[a-zA-Z0-9._]+\\}', 'gi');
interpolate.anyGroup = new RegExp('\\[[a-zA-Z0-9._]+\\]', 'gi');

// -------------------------------------------------------------------------

function request(url, method, data) {  
	method || (method = 'GET');
	
  return new Promise(function(onDone,onError) {
    var xhr = new XMLHttpRequest();

    xhr.onreadystatechange = function() {
      if (xhr.readyState==4) {
        if (xhr.status==200 || xhr.status==0) {          
          onDone(JSON.parse(xhr.responseText));
        } else {
          onError(xhr.status);
        }
      }
    }

    xhr.open(method, url);
    xhr.send(data);
  });
}

// -------------------------------------------------------------------------

function blockchain(API_BASE) {
	this.addAccount = function(privateKey) {
		return request(API_BASE + 'account', 'POST', JSON.stringify({
			privateKey: privateKey,
			token: Date.now(),
			saldo: 0
		}));
	}
	
	this.getAccounts = function() {
		return request(API_BASE + 'account', 'GET');
	}
	 
	this.getBlockchain = function(bcu) {
		return request(API_BASE + 'blockchain?bcu=' + bcu, 'GET');
	}
	
	this.getTranfers = function(bcu) {
		return request(API_BASE + 'tranfer?bcu=' + bcu, 'GET');		
	}
	
	this.addTranfer = function(origen, destino, importe, token) {
		return request(API_BASE + 'tranfer', 'POST', JSON.stringify({
			origen: origen,
			destino: destino,
			importe: importe,
			token: token
		}));		
	}
	
	this.loginAccount = function(bcu, privateKey) {
		return request(API_BASE + 'login', 'POST', JSON.stringify({
			bcu: bcu, 
			privateKey: privateKey
		}));		
	}
	
	this.logoutAccount = function() {
		return new Promise(function(res) {
			localStorage.clear();
			res();			
		});
	}
	
	this.minerGetCommit = function() {
		return request(API_BASE + 'commit', 'GET');		
	}

	this.minerPostCommit = function(payload) {
		return request(API_BASE + 'commit', 'POST', JSON.stringify(payload));		
	}
	
	this.status = function(token) {
		return request(API_BASE + 'status?token=' + token, 'GET');	
	}
}

// -------------------------------------------------------------------------