from fastapi import FastAPI, Request
import cv2
import fastdeploy as fd
import os
import numpy as np
import re

app = FastAPI()

# 运行变量
option = fd.RuntimeOption()
#option.paddle_infer_option.enable_mkldnn = False

# ocr相关
pp_det_file = "/app/models/ch_PP-OCRv3_det_infer"
pp_rec_file = "/app/models/ch_PP-OCRv3_rec_infer"
det_model_file = os.path.join(pp_det_file, "inference.pdmodel")
det_params_file = os.path.join(pp_det_file, "inference.pdiparams")
rec_model_file = os.path.join(pp_rec_file, "inference.pdmodel")
rec_params_file = os.path.join(pp_rec_file, "inference.pdiparams")
rec_label_file = os.path.join(pp_rec_file, "ppocr_keys_v1.txt")

det_model = fd.vision.ocr.DBDetector(det_model_file, det_params_file, runtime_option=option)
rec_model = fd.vision.ocr.Recognizer(rec_model_file, rec_params_file, rec_label_file, runtime_option=option)
ppocr_v3 = fd.vision.ocr.PPOCRv3(det_model=det_model, cls_model=None, rec_model=rec_model)

# 物体识别相关
model_path = "/app/models/hero_detection"
model_file = os.path.join(model_path, "inference.pdmodel")
params_file = os.path.join(model_path, "inference.pdiparams")
config_file = os.path.join(model_path, "inference.yml")
visDetModel = fd.vision.detection.PicoDet(model_file, params_file, config_file)

@app.post('/ocr')
async def ocr(request: Request):
    data = await request.json()
    path = data['path']
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
    res = {'text': text, 'score': score}
    return res

@app.post('/lolKillVisDet')
async def visDet(request: Request):
    data = await request.json()
    path = data['path']
    im = cv2.imread(path)
    result = visDetModel.predict(im)

    scores = np.array(result.scores)
    index = scores > 0.6
    boxes = np.array(result.boxes)[index]
    label_ids = np.array(result.label_ids)[index]

    res = {'boxes': boxes.tolist(), 'labelIds': label_ids.tolist()}
    return res

@app.post('/ping')
async def visDet(request: Request):
    return 'pang'

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='0.0.0.0', port=5000)