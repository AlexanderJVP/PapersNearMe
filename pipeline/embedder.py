"""Sentence embeddings with the exact ONNX model the Android app ships.

Both sides use all-MiniLM-L6-v2 (int8-quantized ONNX), mean pooling over the
attention mask and L2 normalisation, so phone vectors and paper vectors match.
"""
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

MAX_LEN = 256  # keep in sync with WordPieceTokenizer.kt


class Embedder:
    def __init__(self, model_path: str, tokenizer_path: str):
        self.tok = Tokenizer.from_file(tokenizer_path)
        self.tok.enable_truncation(MAX_LEN)
        self.tok.enable_padding(pad_id=0, pad_token="[PAD]")
        self.sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
        self.input_names = {i.name for i in self.sess.get_inputs()}

    def embed(self, texts, batch_size: int = 32) -> np.ndarray:
        chunks = []
        for start in range(0, len(texts), batch_size):
            enc = self.tok.encode_batch(texts[start:start + batch_size])
            ids = np.array([e.ids for e in enc], dtype=np.int64)
            mask = np.array([e.attention_mask for e in enc], dtype=np.int64)
            feeds = {"input_ids": ids, "attention_mask": mask}
            if "token_type_ids" in self.input_names:
                feeds["token_type_ids"] = np.zeros_like(ids)
            hidden = self.sess.run(None, feeds)[0]  # (batch, tokens, 384)
            m = mask[..., None].astype(np.float32)
            vec = (hidden * m).sum(axis=1) / np.clip(m.sum(axis=1), 1e-9, None)
            vec /= np.linalg.norm(vec, axis=1, keepdims=True) + 1e-12
            chunks.append(vec.astype(np.float32))
        if not chunks:
            return np.zeros((0, 384), dtype=np.float32)
        return np.vstack(chunks)
