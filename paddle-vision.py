from fastapi import FastAPI, Request, UploadFile, File, HTTPException
import cv2
import fastdeploy as fd
import os
import numpy as np
import re
import io
from PIL import Image

app = FastAPI()

# 从环境变量获取令牌
HEADER_TOKEN = os.getenv("HEADER_TOKEN")
if not HEADER_TOKEN:
    raise ValueError("HEADER_TOKEN环境变量未设置")

# 中间件：验证所有请求的Authorization头
# @app.middleware("http")
# async def verify_token(request: Request, call_next):
#     exempt_paths = ["/ping"]
#
#     if request.url.path not in exempt_paths:
#         auth_header = request.headers.get("Authorization")
#
#         # 检查Authorization头是否存在且格式正确
#         if not auth_header or not auth_header.startswith("Bearer "):
#             raise HTTPException(
#                 status_code=401,
#                 detail="无效的Authorization头格式，正确格式为: Bearer <token>"
#             )
#
#         # 提取并验证令牌
#         token = auth_header.split("Bearer ")[1].strip()
#         if token != HEADER_TOKEN:
#             raise HTTPException(
#                 status_code=403,
#                 detail="令牌无效或已过期"
#             )
#
#     # 继续处理请求
#     response = await call_next(request)
#     return response

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

def load_image_from_bytes(file_bytes):
    """从字节流加载图片并转换为cv2格式"""
    # 读取字节流为PIL Image
    img = Image.open(io.BytesIO(file_bytes))
    img_cv2 = cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)
    return img_cv2

@app.post('/ocrDet')
async def ocr_det(image: UploadFile = File(...)):
    # 读取图片字节流
    file_bytes = await image.read()
    im = load_image_from_bytes(file_bytes)

    result_list = []
    try:
        result = ppocr_v3.predict(im)
    except:
        print("fuck")
        return result_list

    size = min(len(result.text), len(result.boxes), len(result.rec_scores))
    for i in range(size):
        text_str = result.text[i]
        boxes = result.boxes[i]
        score = float(result.rec_scores[i])
        result_list.append({"boxes": boxes,"text": text_str,"score": score})

    return result_list

@app.post('/lolKillVisDet')
async def vis_det(image: UploadFile = File(...)):
    # 读取图片字节流
    file_bytes = await image.read()
    im = load_image_from_bytes(file_bytes)

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