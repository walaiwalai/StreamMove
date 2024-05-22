from flask import Flask, jsonify, request
import cv2
import fastdeploy as fd
import os
import numpy as np
import re

app = Flask(__name__)

# 运行变量（enable_mkldnn开启多次ocr识别会报错）
option = fd.RuntimeOption()
option.paddle_infer_option.enable_mkldnn = False

# ocr相关
# pp_det_file = "F:/video/models/ch_PP-OCRv3_det_infer"
# pp_rec_file = "F:/video/models/ch_PP-OCRv3_rec_infer"
pp_det_file = "/home/admin/stream/models/ch_PP-OCRv3_det_infer"
pp_rec_file = "/home/admin/stream/models/ch_PP-OCRv3_rec_infer"
det_model_file = os.path.join(pp_det_file, "inference.pdmodel")
det_params_file = os.path.join(pp_det_file, "inference.pdiparams")
rec_model_file = os.path.join(pp_rec_file, "inference.pdmodel")
rec_params_file = os.path.join(pp_rec_file, "inference.pdiparams")
rec_label_file = os.path.join(pp_rec_file, "ppocr_keys_v1.txt")

det_model = fd.vision.ocr.DBDetector(det_model_file, det_params_file, runtime_option=option)
rec_model = fd.vision.ocr.Recognizer(rec_model_file, rec_params_file, rec_label_file, runtime_option=option)
ppocr_v3 = fd.vision.ocr.PPOCRv3(det_model=det_model, cls_model=None, rec_model=rec_model)

# 物体识别相关
# model_path = "F:/video/models/hero_detection"
model_path = "/home/admin/stream/models/hero_detection"
model_file = os.path.join(model_path, "inference.pdmodel")
params_file = os.path.join(model_path, "inference.pdiparams")
config_file = os.path.join(model_path, "inference.yml")
visDetModel = fd.vision.detection.PaddleDetectionModel(model_file, params_file, config_file, runtime_option=option)

@app.route('/ocr', methods=['POST'])
def ocr():
    path = request.get_json()['path']
    im = cv2.imread(path)

    try:
        result = str(ppocr_v3.predict(im))
    except:
        print("fuck")
        result = ""

    text_match = re.search(r'rec text: (\S.+?) rec', result)
    score_match = re.search(r'score:(\d+\.\d+)', result)

    text = text_match.group(1) if text_match else ""
    score = score_match.group(1) if score_match else ""
    res = {}
    res['text'] = text
    res['score'] = score
    return jsonify(res)


@app.route('/lolKillVisDet', methods=['POST'])
def visDet():
    path = request.get_json()['path']
    im = cv2.imread(path)
    result = visDetModel.predict(im)
    
    scores = np.array(result.scores)
    index = scores > 0.6
    boxes = np.array(result.boxes)[index]
    label_ids = np.array(result.label_ids)[index]

    res = {}
    res['boxes'] = boxes.tolist()
    res['labelIds'] = label_ids.tolist()

    return jsonify(res)

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)