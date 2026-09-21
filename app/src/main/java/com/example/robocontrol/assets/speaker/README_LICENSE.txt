3D-Speaker CAM++ speaker embedding model
========================================

File:    campplus_en_voxceleb.onnx  (29.6 MB, 512-dimensional embeddings, 16 kHz)
Source:  https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models
         (file name there: 3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx)
Model:   CAM++ from 3D-Speaker (https://github.com/modelscope/3D-Speaker), Alibaba Speech Lab.
         The 3D-Speaker toolkit is licensed Apache-2.0.
Data:    trained on VoxCeleb, whose terms are research use only. This project is academic work
         (bachelor thesis); a commercial product would need a model trained on other data.

Used by robocontrol/conversation/SpeakerEmbedder.kt to turn a piece of speech into an embedding,
so the robot can tell its owner's voice from another voice. The model is run on the robot with
ONNX Runtime; nothing is sent anywhere.
