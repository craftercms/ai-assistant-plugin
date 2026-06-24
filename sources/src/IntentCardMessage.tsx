import React from 'react';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Typography
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import MarkdownMessage from './MarkdownMessage';
import type { IntentCardModel } from './intentRecipeChatDisplay';

type IntentCardMessageProps = Readonly<{
  card: IntentCardModel;
}>;

export default function IntentCardMessage(props: IntentCardMessageProps) {
  const { card } = props;
  const elaboration = card.elaboration.trim();
  if (!elaboration) return null;

  return (
    <Box>
      <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 0.75 }}>
        Intent
      </Typography>
      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', lineHeight: 1.5 }}>
        {elaboration}
      </Typography>
      {card.anchorPath ? (
        <Typography variant="body2" sx={{ mt: 1 }}>
          <Box component="span" sx={{ fontWeight: 600 }}>
            On page:{' '}
          </Box>
          <Box component="code" sx={{ fontSize: '0.85em' }}>
            {card.anchorPath}
          </Box>
        </Typography>
      ) : null}
      {card.successBars.length > 0 ? (
        <Box sx={{ mt: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.35 }}>
            Success looks like:
          </Typography>
          <Box component="ul" sx={{ m: 0, pl: 2.25 }}>
            {card.successBars.map((bar) => (
              <Typography key={bar} component="li" variant="body2" sx={{ mb: 0.25 }}>
                {bar}
              </Typography>
            ))}
          </Box>
        </Box>
      ) : null}
      {card.willNot.length > 0 ? (
        <Accordion
          disableGutters
          elevation={0}
          sx={{
            mt: 1,
            bgcolor: 'transparent',
            '&:before': { display: 'none' },
            border: 'none'
          }}
        >
          <AccordionSummary
            expandIcon={<ExpandMoreIcon fontSize="small" />}
            sx={{
              minHeight: 0,
              px: 0,
              py: 0,
              '& .MuiAccordionSummary-content': { my: 0.25 }
            }}
          >
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              Other details
            </Typography>
          </AccordionSummary>
          <AccordionDetails sx={{ px: 0, pt: 0, pb: 0.5 }}>
            <Typography variant="caption" sx={{ fontWeight: 600, display: 'block', mb: 0.35 }}>
              I will not:
            </Typography>
            <Box component="ul" sx={{ m: 0, pl: 2.25 }}>
              {card.willNot.map((line) => (
                <Typography key={line} component="li" variant="caption" sx={{ mb: 0.25, display: 'block' }}>
                  {line}
                </Typography>
              ))}
            </Box>
          </AccordionDetails>
        </Accordion>
      ) : null}
      <Typography
        variant="caption"
        component="p"
        sx={{ mt: 1, mb: 0, fontStyle: 'italic', color: 'text.secondary' }}
      >
        Proceeding with tools…
      </Typography>
      {card.recipeWorkflowLine?.trim() ? (
        <Box sx={{ mt: 0.75 }}>
          <MarkdownMessage text={card.recipeWorkflowLine} />
        </Box>
      ) : null}
    </Box>
  );
}
