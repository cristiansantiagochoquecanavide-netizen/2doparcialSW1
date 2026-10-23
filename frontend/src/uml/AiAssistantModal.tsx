import React, { useEffect, useRef, useState } from 'react'
import { DiagramFullView } from './types'
import {
  MicIcon,
  SquareIcon,
  SendIcon,
  SparklesIcon,
  CheckIcon,
  AlertIcon,
  UploadIcon
} from './icons'
import { AiModalShell } from './AiModalShell'

type Props = {
  projectId: string
  diagramId: string
  token: string
  revision?: number
  onClose: () => void
  onDiagramUpdated: (fullView: DiagramFullView) => void
  onError: (err: unknown) => void
  initialMode?: 'command' | 'image' | 'chat'
  initialImageFile?: File | null
  hasExistingContent?: boolean
  selectedElementId?: string | null
  selectedRelationId?: string | null
}

type ImageModel = {
  classes: { name: string; type: string; confidence: number; x: number | null; y: number | null; attributes: { name: string; type: string; visibility: string; confidence: number }[]; methods: { name: string; returnType: string; parameters: string; visibility: string; confidence: number }[] }[]
  relations: { source: string; target: string; type: string; name: string | null; multiplicitySource: string | null; multiplicityTarget: string | null; confidence: number; associationClass?: string | null }[]
  warnings: string[]
}

const IMAGE_MULTIPLICITIES = ['1', '0..1', '*', '0..*', '1..*']
const isManyMultiplicity = (value: string | null | undefined) => value != null && /^(?:\*|n|m|(?:0|1)\.\.(?:\*|n|m))$/i.test(value.trim())

type MessageItem = {
  sender: 'user' | 'ai'
  text: string
  actionType?: string
  success?: boolean
  suggestion?: string
}

export function AiAssistantModal({
  projectId,
  diagramId,
  token,
  revision,
  onClose,
  onDiagramUpdated,
  onError,
  initialMode = 'command',
  initialImageFile = null,
  hasExistingContent = false,
  selectedElementId = null,
  selectedRelationId = null
}: Props) {
  const [messages, setMessages] = useState<MessageItem[]>([
    {
      sender: 'ai',
      text: '¡Hola! Soy tu asistente de modelado UML e ingeniería de software. Puedes pedirme crear clases, agregar atributos, conectar relaciones por voz o texto, reconstruir modelos desde imágenes o resolver dudas conceptuales.'
    }
  ])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)

  // Estados de control de voz manual (Requerimiento 2)
  const [isListening, setIsListening] = useState(false)
  const [voiceStateStatus, setVoiceStateStatus] = useState<'LISTO' | 'ESCUCHANDO' | 'PROCESANDO' | 'EJECUTANDO' | 'ERROR'>('LISTO')
  const [voiceError, setVoiceError] = useState<string | null>(null)
  const [transcriptFinal, setTranscriptFinal] = useState('')
  const [transcriptInterim, setTranscriptInterim] = useState('')

  const mode = initialMode

  const [imageFile, setImageFile] = useState<File | null>(null)
  const [imagePreview, setImagePreview] = useState<string | null>(null)
  const [imagePromptHint, setImagePromptHint] = useState('')
  const [analyzingStatus, setAnalyzingStatus] = useState<string | null>(null)
  const [imageModel, setImageModel] = useState<ImageModel | null>(null)
  const [imageProvider, setImageProvider] = useState<'GROQ' | 'LOCAL' | null>(null)
  const [imageError, setImageError] = useState<string | null>(null)
  const [imageMode, setImageMode] = useState<'ADD' | 'REPLACE'>('ADD')
  const [reconstructResult, setReconstructResult] = useState<{
    success: boolean
    message: string
    classes: number
    relations: number
  } | null>(null)

  const chatEndRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const recognitionRef = useRef<any>(null)
  const isListeningRef = useRef<boolean>(false)
  const finalTranscriptRef = useRef<string>('')
  const interimTranscriptRef = useRef<string>('')
  const voiceSessionRef = useRef<boolean>(false)
  const voiceRunRef = useRef<number>(0)
  const lastFinalChunkRef = useRef<string>('')

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  useEffect(() => {
    if (initialImageFile) loadImageFile(initialImageFile)
  }, [initialImageFile])

  function loadImageFile(file: File) {
    const supportedTypes = ['image/png', 'image/jpeg', 'image/webp', 'image/heic', 'image/heif']
    if (!supportedTypes.includes(file.type) || file.size > 10_000_000) {
      setImageError(file.size > 10_000_000 ? 'La imagen supera el máximo permitido de 10 MB.' : 'Formato no compatible. Usa PNG, JPG, WEBP, HEIC o HEIF.')
      setImageFile(null)
      setImagePreview(null)
      setImageModel(null)
      setImageProvider(null)
      return
    }
    setImageFile(file)
    setImageModel(null)
    setImageProvider(null)
    setImageError(null)
    setReconstructResult(null)
    const reader = new FileReader()
    reader.onload = () => setImagePreview(reader.result as string)
    reader.onerror = () => setImageError('No se pudo leer el archivo de imagen.')
    reader.readAsDataURL(file)
  }

  function handleImageSelect(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    loadImageFile(file)
  }

  async function handleAnalyzeImage() {
    if (!imagePreview || !imageFile || loading) return
    setLoading(true)
    setImageError(null)
    setImageModel(null)
    setImageProvider(null)
    setAnalyzingStatus('Analizando imagen UML…')

    try {
      const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/ai/analyze-image`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`
        },
        body: JSON.stringify({
          imageBase64: imagePreview,
          filename: imageFile.name,
          promptHint: imagePromptHint.trim()
        })
      })

      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        throw new Error(body.message || 'No se pudo analizar la imagen.')
      }
      const data = await res.json() as { model: ImageModel; warnings: string[]; provider: 'GROQ' | 'LOCAL' }
      setAnalyzingStatus(null)
      setImageModel(data.model)
      setImageProvider(data.provider)
    } catch (err) {
      const message = err instanceof Error ? err.message : 'No se pudo analizar la imagen.'
      setImageError(message)
      onError(err)
    } finally {
      setLoading(false)
      setAnalyzingStatus(null)
    }
  }

  function updateImageRelation(index: number, patch: Partial<ImageModel['relations'][number]>) {
    setImageModel(current => {
      if (!current) return current
      const relations = current.relations.map((relation, relationIndex) => relationIndex === index ? { ...relation, ...patch } : relation)
      const updated = relations[index]
      const isNowManyToMany = isManyMultiplicity(updated.multiplicitySource) && isManyMultiplicity(updated.multiplicityTarget)
      const warnings = isNowManyToMany && updated.associationClass
        ? current.warnings.filter(warning => !warning.includes('clase asociativa') || !warning.includes(updated.source) || !warning.includes(updated.target))
        : current.warnings
      return { ...current, relations, warnings }
    })
  }

  async function handleApplyImageModel() {
    if (!imageModel || loading) return

    if (imageMode === 'REPLACE' && hasExistingContent) {
      const confirmed = window.confirm('¿Confirmas reemplazar todo el diagrama actual con las clases detectadas en la imagen?')
      if (!confirmed) return
    }

    setLoading(true)
    setAnalyzingStatus('Aplicando elementos al lienzo…')
    setImageError(null)

    try {
      const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/ai/apply-image-model`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
          'X-Diagram-Revision': String(revision ?? 1)
        },
        body: JSON.stringify({
          mode: imageMode,
          model: imageModel
        })
      })

      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        throw new Error(body.message || 'No se pudo aplicar el modelo al lienzo.')
      }

      const data = await res.json() as {
        success: boolean
        message: string
        classesImported: number
        relationsImported: number
        updatedDiagram: DiagramFullView
      }

      setReconstructResult({
        success: true,
        message: data.message,
        classes: data.classesImported,
        relations: data.relationsImported
      })

      if (data.updatedDiagram) {
        onDiagramUpdated(data.updatedDiagram)
      }
    } catch (err) {
      setImageError(err instanceof Error ? err.message : 'No se pudo procesar el modelo.')
      onError(err)
    } finally {
      setLoading(false)
      setAnalyzingStatus(null)
    }
  }

  // =========================================================================
  // CONTROL MANUAL DE GRABACIÓN DE VOZ CONTINUA (Requerimiento 2)
  // Las pausas NO finalizan la grabación; el usuario controla cuando termina.
  // =========================================================================
  function cleanVoiceText(value: string) {
    return value.replace(/\s+/g, ' ').replace(/\s+([,.;!?])/g, '$1').trim()
  }

  function publishVoiceTranscript() {
    const finalText = cleanVoiceText(finalTranscriptRef.current)
    const interimText = cleanVoiceText(interimTranscriptRef.current)
    setTranscriptFinal(finalText)
    setTranscriptInterim(interimText)
    setInput(cleanVoiceText(`${finalText} ${interimText}`))
  }

  function appendFinalVoiceText(value: string) {
    const chunk = cleanVoiceText(value)
    if (!chunk || chunk === lastFinalChunkRef.current) return
    lastFinalChunkRef.current = chunk
    finalTranscriptRef.current = cleanVoiceText(`${finalTranscriptRef.current} ${chunk}`)
  }

  function startListening() {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
    if (!SpeechRecognition) {
      alert('Tu navegador no soporta la API de reconocimiento de voz por micrófono. Puedes escribir el comando directamente.')
      return
    }

    try {
      setVoiceError(null)
      finalTranscriptRef.current = ''
      interimTranscriptRef.current = ''
      lastFinalChunkRef.current = ''
      setTranscriptFinal('')
      setTranscriptInterim('')
      setInput('')
      voiceSessionRef.current = true
      const runId = voiceRunRef.current + 1
      voiceRunRef.current = runId
      isListeningRef.current = true
      setIsListening(true)
      setVoiceStateStatus('ESCUCHANDO')

      const recognition = new SpeechRecognition()
      recognition.lang = 'es-ES'
      recognition.continuous = true
      recognition.interimResults = true
      recognitionRef.current = recognition

      recognition.onstart = () => {
        if (runId !== voiceRunRef.current) return
        setIsListening(true)
        setVoiceStateStatus('ESCUCHANDO')
      }

      recognition.onresult = (event: any) => {
        if (runId !== voiceRunRef.current) return
        let interimSnippet = ''
        for (let i = event.resultIndex; i < event.results.length; ++i) {
          if (event.results[i].isFinal) {
            appendFinalVoiceText(event.results[i][0].transcript)
          } else {
            interimSnippet += ` ${event.results[i][0].transcript}`
          }
        }
        interimTranscriptRef.current = cleanVoiceText(interimSnippet)
        publishVoiceTranscript()
      }

      recognition.onerror = (event: any) => {
        if (runId !== voiceRunRef.current) return
        if (event.error !== 'no-speech') {
          setVoiceError(`Error de micrófono (${event.error})`)
        }
      }

      recognition.onend = () => {
        if (runId !== voiceRunRef.current) return
        // Si el usuario no ha detenido la grabación manualmente, reiniciar reconocimiento inmediatamente para omitir pausas
        if (isListeningRef.current) {
          try {
            recognition.start()
          } catch {
            // Ya reiniciado
          }
        } else {
          setIsListening(false)
        }
      }

      recognition.start()
    } catch (e) {
      setIsListening(false)
      isListeningRef.current = false
      setVoiceStateStatus('ERROR')
      setVoiceError('No se pudo acceder al micrófono.')
    }
  }

  function stopListening() {
    isListeningRef.current = false
    setIsListening(false)
    interimTranscriptRef.current = ''
    setTranscriptInterim('')
    if (recognitionRef.current) {
      try {
        recognitionRef.current.stop()
      } catch {}
    }
    const finalText = cleanVoiceText(finalTranscriptRef.current)
    setTranscriptFinal(finalText)
    setInput(finalText)
    setVoiceStateStatus('LISTO')
  }

  function cancelListening() {
    voiceRunRef.current += 1
    isListeningRef.current = false
    setIsListening(false)
    try {
      recognitionRef.current?.abort()
    } catch {}
    recognitionRef.current = null
    finalTranscriptRef.current = ''
    interimTranscriptRef.current = ''
    lastFinalChunkRef.current = ''
    setTranscriptFinal('')
    setTranscriptInterim('')
    voiceSessionRef.current = false
    setInput('')
    setVoiceError(null)
    setVoiceStateStatus('LISTO')
  }

  async function handleSend(textToSend?: string) {
    const query = (textToSend || input).trim()
    if (!query || loading) return
    const requestSource = voiceSessionRef.current ? 'VOICE' : 'TEXT'

    const userMsg: MessageItem = { sender: 'user', text: query }
    setMessages((prev) => [...prev, userMsg])
    setInput('')
    finalTranscriptRef.current = ''
    interimTranscriptRef.current = ''
    lastFinalChunkRef.current = ''
    voiceSessionRef.current = false
    setLoading(true)
    setVoiceStateStatus('EJECUTANDO')

    if (!navigator.onLine) {
      setMessages((prev) => [
        ...prev,
        {
          sender: 'ai',
          text:
            mode === 'command'
              ? 'Estás en modo offline (sin conexión a internet). Puedes utilizar los controles directos del lienzo ("+ Clase", "+ Interfaz", edición y relaciones) que funcionan 100% localmente y se sincronizarán automáticamente al reconectar.'
              : 'No hay conexión a internet disponible para la consulta en el servidor. Los cambios en el diagrama se siguen guardando localmente.',
          success: false
        }
      ])
      setLoading(false)
      setVoiceStateStatus('LISTO')
      return
    }

    try {
      if (mode === 'command') {
        const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/ai/command`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${token}`,
            'X-Diagram-Revision': String(revision ?? 1)
          },
          body: JSON.stringify({
            command: query,
            source: requestSource,
            selectedElementId,
            selectedRelationId
          })
        })
        if (!res.ok) {
          const body = await res.json().catch(() => ({}))
          throw new Error(body.message || 'Error al ejecutar comando de IA.')
        }
        const data = await res.json()
        setMessages((prev) => [
          ...prev,
          {
            sender: 'ai',
            text: data.message,
            actionType: data.actionType,
            success: data.success
          }
        ])
        if (data.success && data.updatedDiagram) {
          onDiagramUpdated(data.updatedDiagram)
        }
      } else {
        // Chat conceptual / asesoría
        const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/ai/chat`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${token}`
          },
          body: JSON.stringify({ question: query })
        })
        if (!res.ok) {
          throw new Error('Error al consultar al asesor de IA.')
        }
        const data = await res.json()
        setMessages((prev) => [
          ...prev,
          {
            sender: 'ai',
            text: data.answer,
            suggestion: data.suggestion
          }
        ])
      }
      setVoiceStateStatus('LISTO')
    } catch (err) {
      setVoiceStateStatus('ERROR')
      onError(err)
      setMessages((prev) => [
        ...prev,
        {
          sender: 'ai',
          text: 'Ocurrió un error al procesar la solicitud.',
          success: false
        }
      ])
    } finally {
      setLoading(false)
    }
  }

  const quickPrompts = [
    'Crear clase Cliente con atributos nombre:String, email:String, telefono:String',
    'Crear clase Pedido con atributos fecha:LocalDate, total:Double',
    'Relacionar Cliente con Pedido 1 a N',
    'Crear clase Producto con atributos codigo:String, precio:Double',
    'Explicar diferencia entre Composición y Agregación'
  ]

  return (
    <AiModalShell
      title="Asistente de modelado"
      subtitle={mode === 'command' ? 'Modelado por voz' : mode === 'image' ? 'Reconstruir modelo desde imagen' : 'Asesoría UML'}
      icon={<SparklesIcon size={20} color="#1d4ed8" />}
      onClose={onClose}
    >
        <div className="ai-legacy-header" style={{ display: 'none' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
            <SparklesIcon size={22} color="#1d4ed8" />
            <div>
              <h3 style={{ margin: 0, fontSize: '1.15rem', color: '#0f172a', fontWeight: 'bold' }}>Asistente de modelado</h3>
              <span style={{ fontSize: '0.82rem', color: '#475569' }}>
                {mode === 'command' ? 'Modelado por voz' : mode === 'image' ? 'Reconstruir modelo desde imagen' : 'Asesoría UML'}
              </span>
            </div>
          </div>
          <button type="button" className="close-btn" onClick={onClose} style={{ cursor: 'pointer', fontSize: '1.2rem' }}>
            ✕
          </button>
        </div>

        {/* Panel de reconstrucción desde imagen */}
        {mode === 'image' ? (
          <div className="ai-modal-panel ai-image-panel" style={{ flex: 1, overflowY: 'visible', padding: 0, display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ background: '#f8fafc', border: '1px solid #cbd5e1', borderRadius: '8px', padding: '0.85rem', fontSize: '0.85rem', color: '#1e293b' }}>
              <strong>Imagen → modelo UML:</strong> El modelo de visión (Groq / Qwen) analizará la imagen y devolverá elementos UML estructurados. Podrás revisar el resultado y advertencias antes de aplicarlo al lienzo.
            </div>

            <div
              onClick={() => fileInputRef.current?.click()}
              style={{
                border: '2px dashed #94a3b8',
                borderRadius: '12px',
                padding: '1.25rem',
                textAlign: 'center',
                cursor: 'pointer',
                background: imagePreview ? '#f8fafc' : '#ffffff',
                transition: 'border 0.2s'
              }}
            >
              <input
                ref={fileInputRef}
                type="file"
                accept="image/png,image/jpeg,image/webp,image/heic,image/heif"
                onChange={handleImageSelect}
                style={{ display: 'none' }}
              />

              {imagePreview ? (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '0.5rem' }}>
                  <img
                    src={imagePreview}
                    alt="Diagrama subido"
                    style={{ maxHeight: '180px', maxWidth: '100%', borderRadius: '8px', objectFit: 'contain', border: '1px solid #cbd5e1' }}
                  />
                  <span style={{ fontSize: '0.82rem', color: '#334155', fontWeight: 'bold' }}>
                    Archivo: {imageFile?.name} ({(imageFile ? imageFile.size / 1024 : 0).toFixed(1)} KB) — Clic para cambiar imagen
                  </span>
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '0.5rem', padding: '1rem 0' }}>
                  <UploadIcon size={36} color="#3b82f6" />
                  <span style={{ fontWeight: 'bold', color: '#0f172a', fontSize: '0.95rem' }}>
                    Haz clic aquí o arrastra la imagen de tu diagrama UML
                  </span>
                  <span style={{ fontSize: '0.82rem', color: '#475569' }}>
                    Formatos: PNG, JPG, WEBP, HEIC y HEIF (máximo 10 MB)
                  </span>
                </div>
              )}
            </div>

            {analyzingStatus && (
              <div style={{ padding: '0.75rem 1rem', background: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: '8px', color: '#1d4ed8', fontSize: '0.85rem', display: 'flex', alignItems: 'center', gap: '0.5rem', fontWeight: 'bold' }}>
                <span className="spinner-border spinner-border-sm" role="status" style={{ display: 'inline-block', width: '1rem', height: '1rem', border: '2px solid currentColor', borderRightColor: 'transparent', borderRadius: '50%', animation: 'spin 0.75s linear infinite' }}></span>
                <span>{analyzingStatus}</span>
              </div>
            )}

            {imageError && <div role="alert" style={{ padding: '.75rem 1rem', background: '#fff1f2', border: '1px solid #fecdd3', borderRadius: 8, color: '#9f1239', fontSize: '.85rem', fontWeight: 'bold' }}>{imageError}</div>}

            {imageModel && !analyzingStatus && (
              <div style={{ background: '#f8fafc', border: '1px solid #cbd5e1', borderRadius: '8px', padding: '0.85rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                  <span style={{ fontWeight: 'bold', fontSize: '0.88rem', color: '#0f172a' }}>
                    Modelo detectado ({imageModel.classes.length} elementos, {imageModel.relations.length} relaciones)
                  </span>
                  <span style={{ fontSize: '0.78rem', padding: '0.2rem 0.6rem', background: '#dbeafe', color: '#1e40af', borderRadius: '999px', fontWeight: 'bold' }}>
                    {imageProvider === 'GROQ' ? 'Groq (Qwen) · JSON validado' : 'Fallback local · JSON validado'}
                  </span>
                </div>

                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', marginBottom: '0.5rem' }}>
                  {imageModel.classes.map((c, i) => (
                    <div key={i} style={{ background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: '6px', padding: '0.4rem 0.64rem', fontSize: '0.82rem' }}>
                      <strong style={{ color: '#1d4ed8' }}>{c.name}</strong>
                      <div style={{ fontSize: '0.75rem', color: '#475569', fontWeight: '500' }}>
                        {c.type} · {c.attributes.length} atributos · {c.methods.length} métodos
                      </div>
                    </div>
                  ))}
                </div>

                {imageModel.relations.length > 0 && (
                  <div style={{ fontSize: '0.8rem', color: '#334155', borderTop: '1px dashed #cbd5e1', paddingTop: '0.5rem' }}>
                    <strong>Relaciones — revisa y corrige multiplicidades antes de aplicar:</strong>
                    <div style={{ display: 'grid', gap: '.5rem', marginTop: '.4rem' }}>
                      {imageModel.relations.map((r, i) => (
                        <div key={i} style={{ display: 'grid', gridTemplateColumns: 'minmax(110px,1fr) 78px 78px minmax(130px,1fr)', gap: '.4rem', alignItems: 'center', background: '#ffffff', padding: '.45rem', border: '1px solid #cbd5e1', borderRadius: '6px' }}>
                          <span><strong>{r.source}</strong> → <strong>{r.target}</strong></span>
                          <label style={{ display: 'grid', gap: 2 }}>Origen
                            <select aria-label={`Multiplicidad de origen ${r.source} a ${r.target}`} value={r.multiplicitySource ?? '1'} onChange={event => updateImageRelation(i, { multiplicitySource: event.target.value })}>
                              {IMAGE_MULTIPLICITIES.map(value => <option key={value} value={value}>{value}</option>)}
                            </select>
                          </label>
                          <label style={{ display: 'grid', gap: 2 }}>Destino
                            <select aria-label={`Multiplicidad de destino ${r.source} a ${r.target}`} value={r.multiplicityTarget ?? '1'} onChange={event => updateImageRelation(i, { multiplicityTarget: event.target.value })}>
                              {IMAGE_MULTIPLICITIES.map(value => <option key={value} value={value}>{value}</option>)}
                            </select>
                          </label>
                          {isManyMultiplicity(r.multiplicitySource) && isManyMultiplicity(r.multiplicityTarget) ? (
                            <label style={{ display: 'grid', gap: 2 }}>Clase intermedia
                              <select aria-label={`Clase intermedia de ${r.source} a ${r.target}`} value={r.associationClass ?? ''} onChange={event => updateImageRelation(i, { associationClass: event.target.value || null })}>
                                <option value="">Ninguna</option>
                                {imageModel.classes.map(imageClass => <option key={imageClass.name} value={imageClass.name}>{imageClass.name}</option>)}
                              </select>
                            </label>
                          ) : <span style={{ color: '#64748b' }}>{r.associationClass ? `Clase asociativa: ${r.associationClass}` : r.type}</span>}
                        </div>
                      ))}
                    </div>
                  </div>
                )}
                {imageModel.warnings.length > 0 && (
                  <div style={{ marginTop: '.65rem', padding: '.65rem', background: '#fffbeb', border: '1px solid #fde68a', borderRadius: 6, color: '#92400e', fontSize: '.8rem' }}>
                    <strong style={{ display: 'flex', alignItems: 'center', gap: 4 }}><AlertIcon size={14} /> Advertencias</strong>
                    <ul style={{ margin: '.35rem 0 0', paddingLeft: '1.2rem' }}>
                      {imageModel.warnings.map((warning, i) => <li key={i}>{warning}</li>)}
                    </ul>
                  </div>
                )}
              </div>
            )}

            <div style={{ border: '1px solid #cbd5e1', borderRadius: '8px', padding: '0.65rem 0.85rem', background: '#f8fafc', color: '#334155', fontSize: '0.8rem' }}>
              <strong>GroqCloud (Qwen) en backend · fallback local</strong>
              <div style={{ marginTop: '0.3rem' }}>La clave nunca se envía al navegador. Si Groq no está disponible, se intenta el reconocimiento local; todas las respuestas pasan por validación antes de permitir su aplicación.</div>
            </div>

            {hasExistingContent && (
              <fieldset style={{ border: '1px solid #cbd5e1', borderRadius: 8, padding: '.7rem .85rem', background: '#ffffff' }}>
                <legend style={{ padding: '0 .4rem', fontSize: '.82rem', fontWeight: 700, color: '#0f172a' }}>El diagrama ya tiene contenido</legend>
                <label style={{ display: 'flex', gap: '.5rem', alignItems: 'flex-start', margin: '.35rem 0', fontSize: '.82rem', cursor: 'pointer' }}>
                  <input type="radio" name="image-import-mode" checked={imageMode === 'ADD'} onChange={() => setImageMode('ADD')} />
                  <span><strong>Agregar</strong><br /><small style={{ color: '#475569' }}>Conserva lo actual; no duplica elementos existentes.</small></span>
                </label>
                <label style={{ display: 'flex', gap: '.5rem', alignItems: 'flex-start', margin: '.35rem 0', color: '#9f1239', fontSize: '.82rem', cursor: 'pointer' }}>
                  <input type="radio" name="image-import-mode" checked={imageMode === 'REPLACE'} onChange={() => setImageMode('REPLACE')} />
                  <span><strong>Reemplazar</strong><br /><small style={{ color: '#9f1239' }}>Elimina clases y relaciones actuales; se solicitará confirmación.</small></span>
                </label>
              </fieldset>
            )}

            <div>
              <label style={{ display: 'block', fontSize: '0.82rem', fontWeight: 'bold', color: '#334155', marginBottom: '0.3rem' }}>
                Pista de dominio / contexto (opcional):
              </label>
              <input
                type="text"
                value={imagePromptHint}
                onChange={(e) => setImagePromptHint(e.target.value)}
                placeholder="Ej. 'Comercio Electrónico', 'Cliente, Pedido, Producto, Categoria, Pago'"
                style={{ width: '100%', padding: '0.6rem 0.8rem', borderRadius: '6px', border: '1px solid #cbd5e1', fontSize: '0.85rem' }}
              />
            </div>

            {reconstructResult && (
              <div
                style={{
                  padding: '0.85rem 1rem',
                  borderRadius: '8px',
                  background: reconstructResult.success ? '#dcfce7' : '#fee2e2',
                  border: `1px solid ${reconstructResult.success ? '#86efac' : '#fca5a5'}`,
                  color: reconstructResult.success ? '#14532d' : '#991b1b',
                  fontSize: '0.85rem',
                  fontWeight: 'bold'
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                  {reconstructResult.success ? <CheckIcon size={16} /> : <AlertIcon size={16} />}
                  <span>{reconstructResult.message}</span>
                </div>
                {reconstructResult.classes > 0 && (
                  <div style={{ marginTop: '0.3rem', fontSize: '0.8rem', fontWeight: 'normal' }}>
                    Se insertaron <strong>{reconstructResult.classes} clases</strong> y <strong>{reconstructResult.relations} relaciones</strong> en tu lienzo visual.
                  </div>
                )}
              </div>
            )}

            <div className="ai-image-actions" style={{ marginTop: 'auto', display: 'flex', justifyContent: 'flex-end', gap: '0.75rem' }}>
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => {
                  setImageFile(null)
                  setImagePreview(null)
                  setImageModel(null)
                  setImageProvider(null)
                  setImageError(null)
                  setReconstructResult(null)
                }}
                disabled={loading || !imageFile}
              >
                Limpiar
              </button>
              {!imageModel && (
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={handleAnalyzeImage}
                  disabled={loading || !imageFile || !imagePreview}
                  style={{ minWidth: '180px' }}
                >
                  {loading ? 'Analizando…' : 'Analizar imagen'}
                </button>
              )}
              {imageModel && (
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={handleApplyImageModel}
                  disabled={loading}
                  style={{ minWidth: '210px' }}
                >
                  {loading ? 'Procesando modelo…' : imageMode === 'REPLACE' && hasExistingContent ? 'Reemplazar diagrama' : 'Agregar al diagrama'}
                </button>
              )}
            </div>
          </div>
        ) : (
          <div className="ai-modal-panel ai-chat-panel">
            {/* Historial de conversación */}
            <div className="ai-chat-history" style={{ flex: 1, overflowY: 'auto', padding: '1rem 0', display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
              {messages.map((m, idx) => (
                <div
                  key={idx}
                  style={{
                    alignSelf: m.sender === 'user' ? 'flex-end' : 'flex-start',
                    maxWidth: '85%',
                    background: m.sender === 'user' ? '#1d4ed8' : '#f1f5f9',
                    color: m.sender === 'user' ? '#ffffff' : '#0f172a',
                    padding: '0.75rem 1rem',
                    borderRadius: '12px',
                    fontSize: '0.9rem',
                    lineHeight: '1.45',
                    boxShadow: '0 1px 3px rgba(0,0,0,0.08)'
                  }}
                >
                  {m.actionType && (
                    <div style={{ fontSize: '0.78rem', fontWeight: 'bold', marginBottom: '0.25rem', color: m.success ? (m.sender === 'user' ? '#bbf7d0' : '#15803d') : '#fca5a5' }}>
                      {m.success ? '✓ ' + m.actionType : '⚠ ' + m.actionType}
                    </div>
                  )}
                  <div>{m.text}</div>
                  {m.suggestion && (
                    <div
                      onClick={() => setInput(m.suggestion || '')}
                      style={{ marginTop: '0.5rem', fontSize: '0.8rem', color: m.sender === 'user' ? '#93c5fd' : '#1d4ed8', cursor: 'pointer', textDecoration: 'underline', fontWeight: '600' }}
                    >
                      Sugerencia: {m.suggestion}
                    </div>
                  )}
                </div>
              ))}
              {loading && (
                <div style={{ alignSelf: 'flex-start', color: '#475569', fontSize: '0.85rem', fontStyle: 'italic', fontWeight: 'bold' }}>
                  El asistente está procesando tu solicitud...
                </div>
              )}
              <div ref={chatEndRef} />
            </div>

            {/* Banner de estado de voz */}
            {isListening && (
              <div className="voice-recording-bar">
                <button type="button" className="voice-cancel-button" onClick={cancelListening} title="Cancelar grabaciÃ³n" aria-label="Cancelar grabaciÃ³n">×</button>
                <div className="voice-live-content">
                  <span className="voice-wave" aria-hidden="true"><i /><i /><i /><i /><i /></span>
                  <span className="voice-live-transcript">{cleanVoiceText(`${transcriptFinal} ${transcriptInterim}`) || 'Escuchando...'}</span>
                </div>
                <button
                  type="button"
                  onClick={stopListening}
                  style={{ background: '#dc2626', color: 'white', border: 0, padding: '0.4rem 0.8rem', borderRadius: '6px', cursor: 'pointer', fontWeight: 'bold', fontSize: '0.82rem', display: 'flex', alignItems: 'center', gap: '4px' }}
                >
                  <SquareIcon size={12} />
                </button>
                <button
                  type="button"
                  disabled
                  aria-label="Enviar disponible al detener la grabación"
                  title="Detén la grabación para editar y enviar"
                  style={{ background: '#2563eb', color: 'white', border: 0, padding: '0.55rem 0.7rem', borderRadius: '50%', cursor: 'not-allowed', opacity: 0.55, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
                >
                  <SendIcon size={15} />
                </button>
              </div>
            )}

            {voiceError && (
              <div style={{ padding: '0.5rem 0.8rem', background: '#fee2e2', border: '1px solid #fca5a5', color: '#b91c1c', borderRadius: '6px', fontSize: '0.82rem', fontWeight: 'bold', marginBottom: '0.5rem' }}>
                {voiceError}
              </div>
            )}

            {/* Sugerencias rápidas */}
            <div style={{ display: 'flex', gap: '0.4rem', overflowX: 'auto', paddingBottom: '0.5rem' }}>
              {quickPrompts.slice(0, 3).map((prompt, i) => (
                <button
                  key={i}
                  type="button"
                  className="btn-sm"
                  style={{ fontSize: '0.78rem', whiteSpace: 'nowrap', background: '#ffffff', borderColor: '#cbd5e1', color: '#1e293b', fontWeight: '500' }}
                  onClick={() => setInput(prompt)}
                >
                  {prompt}
                </button>
              ))}
            </div>

            {/* Input con micrófono manual y envío */}
            {!isListening && <form className="ai-chat-composer"
              onSubmit={(e) => {
                e.preventDefault()
                if (!isListening) handleSend()
              }}
              style={{ display: 'flex', gap: '0.5rem', paddingTop: '0.75rem', borderTop: '1px solid #cbd5e1' }}
            >
              {!isListening ? (
                <button
                  type="button"
                  onClick={startListening}
                  title="Hablar por micrófono (Control manual: las pausas no cortan la grabación)"
                  style={{
                    padding: '0.6rem 0.9rem',
                    borderRadius: '8px',
                    border: '1px solid #cbd5e1',
                    background: '#f8fafc',
                    color: '#1d4ed8',
                    cursor: 'pointer',
                    fontWeight: 'bold',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                    fontSize: '0.85rem'
                  }}
                >
                  <MicIcon size={16} color="#1d4ed8" /> Voz
                </button>
              ) : null}

              <input
                type="text"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                readOnly={isListening}
                placeholder={
                  mode === 'command'
                    ? 'Ej. Crear clase Producto con nombre:String, precio:Double'
                    : 'Haz una pregunta sobre modelado UML o arquitectura...'
                }
                style={{ flex: 1, padding: '0.6rem 0.9rem', borderRadius: '8px', border: '1px solid #cbd5e1', fontSize: '0.9rem', color: '#0f172a', fontWeight: '500' }}
              />

              <button
                type="submit"
                disabled={loading || isListening || !input.trim()}
                className="btn btn-primary"
                style={{ padding: '0.6rem 1.2rem', display: 'inline-flex', alignItems: 'center', gap: '6px', fontWeight: 'bold' }}
              >
                <SendIcon size={14} /> Enviar
              </button>
            </form>}
          </div>
        )}
    </AiModalShell>
  )
}
