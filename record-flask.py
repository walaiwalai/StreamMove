# from flask import Flask, request, jsonify
# import redis
# import subprocess
# import threading
# import uuid
#
# app = Flask(__name__)
# redis_client = redis.StrictRedis(host='127.0.0.1', port=6379, db=0, password='123456')
#
# def execute_command(command, job_id, expire_time):
#     try:
#         result = subprocess.run(command, shell=True, capture_output=True, text=True)
#         redis_client.setex(job_id, 2 * expire_time, result.returncode)
#     except Exception as e:
#         redis_client.setex(job_id, 2 * expire_time, str(e))
#
# @app.route('/executeAsync', methods=['POST'])
# def executeAsync():
#     data = request.json
#     command = data.get('command')
#     expire_time = data.get('expire_seconds', 3600)
#
#     job_id = 'cmd-' + str(uuid.uuid4())
#     redis_client.set(job_id, "cmd_running")
#     threading.Thread(target=execute_command, args=(command, job_id, expire_time)).start()
#
#     return jsonify({'job_id': job_id})
#
#
# @app.route('/execute', methods=['POST'])
# def execute():
#     data = request.json
#     command = data.get('command')
#     expire_time = data.get('expire_seconds', 3600)
#
#     job_id = 'cmd-' + str(uuid.uuid4())
#
#     try:
#         # 同步执行命令
#         result = subprocess.run(command, shell=True, capture_output=True, text=True)
#         return jsonify({'job_id': job_id, 'return_code': result.returncode, 'output': result.stdout, 'error': result.stderr})
#     except Exception as e:
#         return jsonify({'job_id': job_id, 'return_code': 999, 'error': str(e)})
#
# if __name__ == '__main__':
#     app.run(host='0.0.0.0', port=6000)
